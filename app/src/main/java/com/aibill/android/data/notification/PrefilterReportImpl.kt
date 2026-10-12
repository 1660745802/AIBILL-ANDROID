package com.aibill.android.data.notification

import com.aibill.android.BuildConfig
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.FilterCountDto
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.request.FilterTotalsDto
import com.aibill.android.domain.notification.CaptureFunnelStats
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
 *    （新数据继续留在 [stats] / [captureFunnelStats]，不丢）。这样每个请求的窗口跨度天然 ≤ 两次 worker 间隔。
 * 2. **失败只记日志**：上报是旁路，任何异常都不能影响用户正在做的事；取消原样传播。
 * 3. **每渠道一条**：契约 §4 的 `channel` 是入口归因（nls/sms/a11y）。
 * 4. **约束自证**：`counts` 超 200 拆分多次（不截断）；只发 `precheck.*`/`client_only.*`
 *    命名空间（防 400）；窗口时间串固定北京墙钟；跨度防御性截断到 31 天。
 *
 * `precheck_unknown_expr` / `precheck_unknown_ids` 是**纯本地诊断**，不满足服务端命名空间，
 * 只进 [PrefilterStats] 与日志，**不进入** `counts`。
 *
 * ## 捕获层漏斗计数（[CaptureFunnelStats]）如何并入
 *
 * 捕获层 stage 由 [CaptureFunnelStats.Snapshot.reportCountsFor] 机械映射成
 * `client_only.capture_*`，与本窗口的预筛 `counts` **合并后一起发**（同一窗口、同一渠道）。
 * 取走即 [CaptureFunnelStats.reset]；上报失败时计数随 [Window] 一起留在 [pending] 重发。
 *
 * ⚠️ **totals 取舍**：捕获层计数**只进 `counts`，不参与 `totals`**。
 * `totals.notifications` 的语义是「收到的通知数」，服务端硬约束是 `blocked <= notifications`；
 * 而捕获层 `received.*` 计的是**平台事件数**（A11Y 一次页面刷新会连发多条事件）、
 * `dropped.*` 是各早退分支**独立计数**之和，两者都无法在本地保证 ≤ notifications。
 * 因此这里让 `notifications = blockedTotal + passed`（纯预筛口径，恒满足 blocked ≤ notifications），
 * 捕获层只作为 `client_only.capture_*` 的**观察量**上报；服务端要拿漏斗对账时按前缀剥离即可，
 * 不会因为捕获层计数把一个合法请求变成 400。
 */
@Singleton
class PrefilterReportImpl @Inject constructor(
    private val stats: PrefilterStats,
    private val captureFunnelStats: CaptureFunnelStats,
    private val aiApi: AiApi,
    private val appLogger: AppLogger,
) : PrefilterReport {

    /**
     * 一个上报窗口 = 预筛计数 + 捕获层漏斗计数。
     *
     * 两者必须绑定成一个整体：[pending] 重发时若只保留预筛那半，捕获层的账就永远丢了。
     */
    private data class Window(
        val prefilter: PrefilterStats.Snapshot,
        /** 已按渠道分好的 `client_only.capture_*` 计数（reportKey → count）。 */
        val capture: Map<SourceChannel, Map<String, Long>>,
    )

    /** 上次没发成功的窗口；下次先重发它（边界原样保留）。 */
    private var pending: Window? = null

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
        pending = sendWindow(
            Window(prefilter = stats.drain(now), capture = drainCaptureCounts()),
        )
    }

    /**
     * 取走捕获层计数并清零。
     *
     * [CaptureFunnelStats] 只有 snapshot/reset（没有 drain）：这里「先快照后清零」等价于一次 drain；
     * 发送失败时这些计数已经进入 [Window]，由 [pending] 保留重发，不会丢。
     */
    private fun drainCaptureCounts(): Map<SourceChannel, Map<String, Long>> {
        val snapshot = captureFunnelStats.snapshot()
        captureFunnelStats.reset()
        return SourceChannel.entries
            .associateWith { channel -> snapshot.reportCountsFor(channel) }
            .filterValues { it.isNotEmpty() }
    }

    /** 发送整个窗口；返回 null = 全部成功，否则返回仍需重发的按渠道子集。 */
    private suspend fun sendWindow(window: Window): Window? {
        val channels = LinkedHashSet<SourceChannel>()
        channels += window.prefilter.channels.keys
        channels += window.capture.keys
        val failedPrefilter = LinkedHashMap<SourceChannel, PrefilterStats.Snapshot.ChannelCounts>()
        val failedCapture = LinkedHashMap<SourceChannel, Map<String, Long>>()
        for (channel in channels) {
            val counts = window.prefilter.channels[channel] ?: EMPTY_CHANNEL_COUNTS
            val captureCounts = window.capture[channel].orEmpty()
            // 预筛空不代表没得发：只要有捕获层计数就要发（否则 A11Y 全早退的窗口永远不可见）。
            if (isEffectivelyEmpty(counts) && captureCounts.isEmpty()) continue
            if (!sendChannel(channel, window.prefilter.windowStart, window.prefilter.windowEnd, counts, captureCounts)) {
                failedPrefilter[channel] = counts
                failedCapture[channel] = captureCounts
            }
        }
        return if (failedPrefilter.isEmpty() && failedCapture.isEmpty()) {
            null
        } else {
            Window(window.prefilter.copy(channels = failedPrefilter), failedCapture)
        }
    }

    private suspend fun sendChannel(
        channel: SourceChannel,
        windowStart: Long,
        windowEnd: Long,
        counts: PrefilterStats.Snapshot.ChannelCounts,
        captureCounts: Map<String, Long>,
    ): Boolean {
        // 只发契约命名空间：第三个前缀服务端 400，且不截断。（纯本地诊断键在此被丢弃。）
        val reportable = counts.blocked.filterKeys(PrefilterReport::isReportableRuleId)
        val dropped = counts.blocked.keys - reportable.keys
        if (dropped.isNotEmpty()) {
            appLogger.warn("REPORT", "丢弃非契约命名空间的 rule_id（防 400，不上报）: $dropped")
        }
        // 捕获层键已是 client_only.*（CaptureFunnelStats.reportKey）；同键合并防御重复。
        val merged = LinkedHashMap<String, Long>(reportable)
        captureCounts.forEach { (key, value) -> merged.merge(key, value, Long::plus) }
        // 没有任何可报键（例如整窗只有 passed）→ 不构造空 counts 请求（服务端要求 1..200）。
        if (merged.isEmpty()) return true

        // totals 口径只取预筛（见类 KDoc「totals 取舍」）：blocked <= notifications 恒成立。
        val blockedTotal = reportable.values.sum()
        val totals = FilterTotalsDto(
            // 分母口径 = 本渠道「放行 + 拦截」，必然满足 blocked <= notifications
            notifications = blockedTotal + counts.passed,
            blocked = blockedTotal,
        )
        val entries = merged.entries
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

    private companion object {
        /** 某渠道只有捕获层计数、没有预筛记录时的空占位。 */
        val EMPTY_CHANNEL_COUNTS = PrefilterStats.Snapshot.ChannelCounts(
            blocked = emptyMap(),
            unusableRules = emptyMap(),
            passed = 0L,
        )
    }
}
