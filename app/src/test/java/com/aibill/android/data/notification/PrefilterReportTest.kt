package com.aibill.android.data.notification

import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.FilterReportResponseDto
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * ⑤ 拦截计数上报（跨端契约 §4 + 服务端端点已落地的硬约束）。
 *
 * - **按渠道拆窗**：`channel` 是入口归因，每渠道一条；
 * - **窗口是北京时间串**（`YYYY-MM-DD HH:MM:SS`，固定 +08:00），不是 epoch / 设备本地时区；
 * - **形状**：`counts[{rule_id,count}]` + `totals{notifications,blocked}`；
 * - **约束**：`counts` ≤200（超限拆分不截断）、命名空间只允许 `precheck.*`/`client_only.*`、
 *   `blocked <= notifications`、窗口跨度 ≤31 天；
 * - **best-effort**：失败保留窗口下次重发；回执 `unknown_precheck_ids` 只告警计数、不重试。
 */
class PrefilterReportTest {

    private val aiApi = mockk<AiApi>()
    private val logger = AppLogger(mockk(relaxed = true))

    /** 记录每次发出的请求；[failWhen] 返回 true 时该次调用抛 IOException（模拟离线）。 */
    private fun reporter(
        stats: PrefilterStatsImpl,
        captureFunnelStats: CaptureFunnelStatsImpl = CaptureFunnelStatsImpl(),
        failWhen: () -> Boolean = { false },
        unknownIds: List<String>? = null,
    ): Pair<PrefilterReportImpl, MutableList<FilterReportRequest>> {
        val requests = mutableListOf<FilterReportRequest>()
        val slot = slot<FilterReportRequest>()
        coEvery { aiApi.filterReport(capture(slot)) } answers {
            requests += slot.captured
            if (failWhen()) throw IOException("offline")
            ApiResponse(code = 0, data = FilterReportResponseDto(accepted = 1, unknownPrecheckIds = unknownIds), message = "ok")
        }
        return PrefilterReportImpl(
            stats = stats,
            captureFunnelStats = captureFunnelStats,
            aiApi = aiApi,
            appLogger = logger,
        ) to requests
    }

    @Test
    @DisplayName("所有 BlockReason 的 client_only.* 键都落在服务端命名空间内（缺一个就 400）")
    fun everyBlockReasonMapsToReportableNamespace() {
        for (reason in com.aibill.android.domain.notification.BlockReason.entries) {
            val key = PrefilterStats.clientOnlyKey(reason)
            assertTrue(
                PrefilterReport.isReportableRuleId(key),
                "BlockReason.$reason → $key 不在 precheck.*/client_only.* 命名空间",
            )
            assertTrue(key.startsWith("client_only."), key)
        }
        // 纯本地诊断键**不**满足命名空间（因此不会被上报，防止 400）
        assertFalse(PrefilterReport.isReportableRuleId(PrefilterStats.PRECHECK_UNKNOWN_EXPR))
        assertFalse(PrefilterReport.isReportableRuleId(PrefilterStats.PRECHECK_UNKNOWN_IDS))
        assertTrue(PrefilterReport.isReportableRuleId("precheck.money_only"))
    }

    @Test
    @DisplayName("窗口时间口径：无时区串、固定北京 +08:00（不发 epoch / 设备本地时区）")
    fun windowTimeIsBeijingWallClock() {
        assertEquals("1970-01-01 08:00:00", PrefilterReport.formatWindowTime(0L))
        assertEquals("2026-10-12 23:40:00", PrefilterReport.formatWindowTime(1_791_819_600_000L))
        // 契约接受的无时区形式
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""").matches(PrefilterReport.formatWindowTime(1_791_819_600_000L)))
    }

    @Test
    @DisplayName("按渠道各报一条，形状符合契约 §4（counts + totals + 时间串）")
    fun reportsPerChannelWithContractShape() = runTest {
        val now = 1_791_819_600_000L
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, "precheck.money_only")
        stats.recordBlocked(SourceChannel.NLS, "client_only.MARKETING")
        stats.recordPassed(SourceChannel.NLS)
        stats.recordBlocked(SourceChannel.SMS, "precheck.strong_noise")
        val (reporter, requests) = reporter(stats)

        reporter.report(now = now)

        assertEquals(2, requests.size, "每渠道一条：$requests")
        val nls = requests.first { it.channel == "nls" }
        assertEquals(
            listOf("client_only.MARKETING" to 1L, "precheck.money_only" to 1L),
            nls.counts.map { it.ruleId to it.count }.sortedBy { it.first },
        )
        assertEquals(3L, nls.totals.notifications)
        assertEquals(2L, nls.totals.blocked)
        assertEquals(PrefilterReport.formatWindowTime(now), nls.windowEnd)
        assertEquals(com.aibill.android.BuildConfig.VERSION_NAME, nls.clientVersion)
        assertTrue(nls.totals.blocked <= nls.totals.notifications)

        val sms = requests.first { it.channel == "sms" }
        assertEquals(1L, sms.totals.blocked)
        assertEquals(1L, sms.totals.notifications)
    }

    @Test
    @DisplayName("上报成功后窗口清零（不重复上报）")
    fun successfulReportDrainsWindow() = runTest {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, "precheck.money_only")
        val (reporter, requests) = reporter(stats)

        reporter.report(now = 1_000_000L)
        reporter.report(now = 2_000_000L) // 窗口已空 → 不再发

        assertEquals(1, requests.size, "清零后不应重复上报：$requests")
    }

    @Test
    @DisplayName("best-effort：失败保留原窗口，下次先重发原窗口再发新窗口（边界不变）")
    fun failedReportIsRetriedWithSameBounds() = runTest {
        val stats = PrefilterStatsImpl()
        var offline = true
        val (reporter, requests) = reporter(stats, failWhen = { offline })

        stats.recordBlocked(SourceChannel.NLS, "precheck.money_only")
        reporter.report(now = 1_000_000L) // 失败，不上抛
        assertEquals(1, requests.size)

        offline = false
        stats.recordBlocked(SourceChannel.NLS, "precheck.strong_noise")
        reporter.report(now = 2_000_000L)

        assertEquals(3, requests.size, "原窗口重发 + 新窗口：$requests")
        val first = requests[0]
        val retried = requests[1]
        assertEquals(first.windowStart, retried.windowStart)
        assertEquals(first.windowEnd, retried.windowEnd)
        assertEquals(listOf("precheck.money_only"), retried.counts.map { it.ruleId })
        assertEquals(listOf("precheck.strong_noise"), requests[2].counts.map { it.ruleId })
    }

    @Test
    @DisplayName("counts 超 200：拆分成多个请求（不截断）")
    fun countsAreChunkedNotTruncated() = runTest {
        val stats = PrefilterStatsImpl()
        repeat(201) { i -> stats.recordBlocked(SourceChannel.NLS, "client_only.R$i") }
        val (reporter, requests) = reporter(stats)

        reporter.report(now = 1_000_000L)

        assertEquals(2, requests.size, "201 条应拆成 2 个请求：${requests.map { it.counts.size }}")
        assertEquals(200, requests[0].counts.size)
        assertEquals(1, requests[1].counts.size)
        // 两个分片覆盖 201 个不同 rule_id，无截断
        assertEquals(201, requests.flatMap { it.counts }.map { it.ruleId }.toSet().size)
        // totals 每个分片都成立
        requests.forEach { assertEquals(201L, it.totals.blocked) }
    }

    @Test
    @DisplayName("非契约命名空间的 rule_id 不上报（防 400），不截断其它键")
    fun nonContractNamespaceIsDropped() = runTest {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, "bogus.key")
        stats.recordBlocked(SourceChannel.NLS, PrefilterStats.PRECHECK_UNKNOWN_EXPR) // 纯本地诊断
        stats.recordBlocked(SourceChannel.NLS, "precheck.money_only")
        val (reporter, requests) = reporter(stats)

        reporter.report(now = 1_000_000L)

        val sent = requests.single().counts.map { it.ruleId }
        assertEquals(listOf("precheck.money_only"), sent, "只发 precheck.*/client_only.*：$sent")
        assertEquals(1L, requests.single().totals.blocked)
    }

    @Test
    @DisplayName("回执 unknown_precheck_ids：warn + 本地诊断计数，不重试")
    fun unknownPrecheckIdsAreConsumedNotRetried() = runTest {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, "precheck.deleted_on_server")
        val (reporter, requests) = reporter(stats, unknownIds = listOf("precheck.deleted_on_server"))

        reporter.report(now = 1_000_000L)

        assertEquals(1, requests.size, "已收下就不该重试")
        val diagnostics = stats.snapshot().channels.getValue(SourceChannel.NLS).unusableRules
        assertEquals(1L, diagnostics[PrefilterStats.PRECHECK_UNKNOWN_IDS], diagnostics.toString())
        // 诊断不是拦截：它不被计入 blocked（拦截计数已被 drain 上报并清零）
        assertEquals(0L, stats.snapshot().blockedTotal)
    }

    @Test
    @DisplayName("窗口内没有记录 → 完全不发请求（也不发空 counts）")
    fun emptyWindowSendsNothing() = runTest {
        val stats = PrefilterStatsImpl()
        val (reporter, requests) = reporter(stats)
        reporter.report(now = 1_000_000L)
        assertTrue(requests.isEmpty())
    }

    @Test
    @DisplayName("捕获层漏斗 stage 也能上报，且机械映射落在 client_only.capture_*（不污染 totals）")
    fun captureFunnelStagesAreReportedUnderClientOnly() = runTest {
        val stats = PrefilterStatsImpl()
        val capture = CaptureFunnelStatsImpl()
        // A11Y：一页连发多个事件 → 大量白名单外早退 + 命中/未命中成功词
        repeat(7) { capture.record(CaptureFunnelStats.RECEIVED_A11Y) }
        repeat(5) { capture.record(CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED) }
        capture.record(CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD)
        // NLS 侧也有捕获层早退
        capture.record(CaptureFunnelStats.RECEIVED_NLS)
        capture.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)
        val (reporter, requests) = reporter(stats, captureFunnelStats = capture)

        reporter.report(now = 1_000_000L)

        assertEquals(2, requests.size, "每渠道一条（预筛为空，但仍要上报捕获层）：$requests")
        val a11y = requests.first { it.channel == "a11y" }
        val a11yKeys = a11y.counts.map { it.ruleId }
        assertTrue(a11yKeys.all { it.startsWith("client_only.") }, a11yKeys.toString())
        assertEquals(
            mapOf(
                "client_only.capture_received_a11y" to 7L,
                "client_only.capture_dropped_a11y_not_whitelisted" to 5L,
                "client_only.capture_dropped_a11y_no_success_keyword" to 1L,
            ),
            a11y.counts.associate { it.ruleId to it.count },
        )
        // totals 是纯预筛口径：捕获层计数只进 counts，不破坏 blocked <= notifications
        assertEquals(0L, a11y.totals.blocked)
        assertEquals(0L, a11y.totals.notifications)
        assertTrue(a11y.totals.blocked <= a11y.totals.notifications)

        val nls = requests.first { it.channel == "nls" }
        assertEquals(
            mapOf(
                "client_only.capture_received_nls" to 1L,
                "client_only.capture_dropped_nls_no_extras" to 1L,
            ),
            nls.counts.associate { it.ruleId to it.count },
        )
        // 捕获层被取走后清零：再 report 一次不应重复上报
        reporter.report(now = 2_000_000L)
        assertEquals(2, requests.size, "捕获层计数不应重复上报：$requests")
    }

    @Test
    @DisplayName("捕获层计数与预筛计数合并进同一 counts（同键求和防御）")
    fun captureFunnelCountsMergeWithPrefilterCounts() = runTest {
        val stats = PrefilterStatsImpl()
        val capture = CaptureFunnelStatsImpl()
        stats.recordBlocked(SourceChannel.SMS, "client_only.MARKETING")
        stats.recordPassed(SourceChannel.SMS)
        repeat(3) { capture.record(CaptureFunnelStats.RECEIVED_SMS) }
        capture.record(CaptureFunnelStats.DROPPED_SMS_BLANK_TEXT)
        val (reporter, requests) = reporter(stats, captureFunnelStats = capture)

        reporter.report(now = 1_000_000L)

        val sms = requests.single()
        assertEquals(
            mapOf(
                "client_only.MARKETING" to 1L,
                "client_only.capture_received_sms" to 3L,
                "client_only.capture_dropped_sms_blank_text" to 1L,
            ),
            sms.counts.associate { it.ruleId to it.count },
        )
        // totals 仍只算预筛：拦截 1 + 放行 1 → notifications = 2（捕获层不参与）
        assertEquals(1L, sms.totals.blocked)
        assertEquals(2L, sms.totals.notifications)
    }

    @Test
    @DisplayName("窗口跨度防御性截断到 31 天（服务端约束）")
    fun windowSpanIsClamped() {
        val end = 10_000_000_000_000L
        val tooEarly = end - 60L * 24 * 60 * 60 * 1000
        val clamped = PrefilterReport.clampWindowStart(tooEarly, end)
        assertFalse(clamped < end - PrefilterReport.MAX_WINDOW_SPAN_DAYS * 24 * 60 * 60 * 1000)
        // 正常跨度不动
        val recent = end - 60_000L
        assertEquals(recent, PrefilterReport.clampWindowStart(recent, end))
    }
}
