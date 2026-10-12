package com.aibill.android.data.notification

import com.aibill.android.BuildConfig
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.FilterCountDto
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.request.FilterTotalsDto
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PrefilterReport] 的实现：窗口快照 → 按渠道 `POST /api/ai/filter-report`。
 *
 * ## 四个刻意的设计（对齐服务端已落地的硬约束）
 *
 * 1. **原窗口重发**：某渠道没发成功时把该窗口原样留在 [pending]，下次先重发它
 *    （边界不变 → 服务端 upsert 幂等），成功后再发新窗口。失败期间**不 drain**
 *    （新数据继续留在 [stats]，不丢）。这样每个请求的窗口跨度天然 ≤ 两次 worker 间隔。
 * 2. **失败只记日志**：上报是旁路，任何异常都不能影响用户正在做的事；取消原样传播。
 * 3. **每渠道一条**：契约 §4 的 `channel` 是入口归因（nls/sms/a11y）。
 * 4. **约束自证**：`counts` 超 200 拆分多次（不截断）；只发 `precheck.*`/`client_only.*`
 *    命名空间（防 400）；窗口时间串固定北京墙钟；跨度防御性截断到 31 天。
 *
 * `precheck_unknown_expr` / `precheck_unknown_ids` 是**纯本地诊断**，不满足服务端命名空间，
 * 只进 [PrefilterStats] 与日志，**不进入** `counts`。
 */
@Singleton
class PrefilterReportImpl @Inject constructor(
    private val stats: PrefilterStats,
    private val aiApi: AiApi,
    private val appLogger: AppLogger,
) : PrefilterReport {

    /** 上次没发成功的窗口；下次先重发它（边界原样保留）。 */
    private var pending: PrefilterStats.Snapshot? = null

    override suspend fun report(now: Long) {
        // 1) 先尽力重发上次失败的窗口；还没成功就保留并返回（新数据留在 stats 继续累积）。
        pending?.let { carried ->
            val failed = sendWindow(carried)
            if (failed != null) {
                pending = failed
                return
            }
            pending = null
        }
        // 2) 再发新窗口；失败则原样保留（各自边界，跨度不会是「失败天数叠加」）。
        pending = sendWindow(stats.drain(now))
    }

    /** 发送整个窗口；返回 null = 全部成功，否则返回仍需重发的按渠道子集。 */
    private suspend fun sendWindow(window: PrefilterStats.Snapshot): PrefilterStats.Snapshot? {
        if (window.channels.isEmpty()) return null
        val failed = LinkedHashMap<SourceChannel, PrefilterStats.Snapshot.ChannelCounts>()
        for ((channel, counts) in window.channels) {
            if (isEffectivelyEmpty(counts)) continue
            if (!sendChannel(channel, window.windowStart, window.windowEnd, counts)) {
                failed[channel] = counts
            }
        }
        return if (failed.isEmpty()) null else window.copy(channels = failed)
    }

    private suspend fun sendChannel(
        channel: SourceChannel,
        windowStart: Long,
        windowEnd: Long,
        counts: PrefilterStats.Snapshot.ChannelCounts,
    ): Boolean {
        // 只发契约命名空间：第三个前缀服务端 400，且不截断。（纯本地诊断键在此被丢弃。）
        val reportable = counts.blocked.filterKeys(PrefilterReport::isReportableRuleId)
        val dropped = counts.blocked.keys - reportable.keys
        if (dropped.isNotEmpty()) {
            appLogger.warn("REPORT", "丢弃非契约命名空间的 rule_id（防 400，不上报）: $dropped")
        }
        // 没有任何 rule_id 可报（例如整窗只有 passed）→ 不构造空 counts 请求（服务端要求 1..200）。
        if (reportable.isEmpty()) return true

        val blockedTotal = reportable.values.sum()
        val totals = FilterTotalsDto(
            // 分母口径 = 本渠道「放行 + 拦截」，必然满足 blocked <= notifications
            notifications = blockedTotal + counts.passed,
            blocked = blockedTotal,
        )
        val entries = reportable.entries
            .map { FilterCountDto(ruleId = it.key, count = it.value) }
            .sortedBy { it.ruleId }
        val start = PrefilterReport.clampWindowStart(windowStart, windowEnd)
        val startText = PrefilterReport.formatWindowTime(start)
        val endText = PrefilterReport.formatWindowTime(windowEnd)

        var allSent = true
        for (chunk in entries.chunked(PrefilterReport.MAX_COUNTS_PER_REQUEST)) {
            if (!sendOne(channel, startText, endText, chunk, totals)) allSent = false
        }
        return allSent
    }

    private suspend fun sendOne(
        channel: SourceChannel,
        windowStart: String,
        windowEnd: String,
        counts: List<FilterCountDto>,
        totals: FilterTotalsDto,
    ): Boolean {
        val request = FilterReportRequest(
            windowStart = windowStart,
            windowEnd = windowEnd,
            clientVersion = BuildConfig.VERSION_NAME,
            channel = PrefilterReport.channelName(channel),
            counts = counts,
            totals = totals,
        )
        return try {
            val response = aiApi.filterReport(request)
            if (response.code == 0) {
                consumeUnknownPrecheckIds(response.data?.unknownPrecheckIds)
                appLogger.info(
                    "REPORT",
                    "预筛计数已上报: ch=${request.channel} rules=${counts.size} " +
                        "blocked=${totals.blocked} notifications=${totals.notifications}",
                )
                true
            } else {
                appLogger.warn("REPORT", "预筛计数上报失败 code=${response.code} msg=${response.message}，保留到下次")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.warn("REPORT", "预筛计数上报异常（保留到下次）: ${e.message}")
            false
        }
    }

    /**
     * 回执 `unknown_precheck_ids` = 我们上报的 `precheck.*` 不在服务端当前清单里。
     *
     * 它是**双向漂移信号**（客户端用了旧清单 / 服务端删了判据）：服务端已收下，
     * 所以**绝不因此失败重试**——只 warn 记 id + 计入本地诊断计数。
     */
    private fun consumeUnknownPrecheckIds(ids: List<String>?) {
        if (ids.isNullOrEmpty()) return
        appLogger.warn("REPORT", "服务端不认识这些 precheck 规则（客户端清单可能过期）: $ids")
        stats.recordUnknownPrecheckIds()
    }

    private fun isEffectivelyEmpty(counts: PrefilterStats.Snapshot.ChannelCounts): Boolean =
        counts.blocked.isEmpty() && counts.passed == 0L
}
