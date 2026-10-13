package com.aibill.android.data.notification

import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.SourceChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 捕获层漏斗计数（PRODUCT §三「漏账率」缺的那块：adapter 早退的每一层）。
 *
 * 三个层面：
 * - [CaptureFunnelStatsImpl] 自身：入口 `received.*` 与早退 `dropped.*` 分开计数、可清零；
 * - **映射**：stage → `client_only.capture_*` 必须落在服务端命名空间内（否则 ⑤ 上报 400）；
 * - **并发**：三渠道并发调用不能丢计数（NLS/SMS/A11Y 各在一条协程上）。
 */
class CaptureFunnelStatsImplTest {

    @Test
    @DisplayName("入口与早退分开计数：received 按渠道，stages 只含 dropped.*")
    fun countsReceivedAndDroppedSeparately() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_A11Y)
        repeat(9) { stats.record(CaptureFunnelStats.RECEIVED_A11Y) }
        repeat(5) { stats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED) }
        stats.record(CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD)
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)

        val snap = stats.snapshot()
        assertEquals(10L, snap.received.getValue(SourceChannel.A11Y))
        assertEquals(1L, snap.received.getValue(SourceChannel.NLS))
        assertEquals(0L, snap.received.getValue(SourceChannel.SMS))
        assertEquals(11L, snap.receivedTotal)
        assertEquals(5L, snap.stages[CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED])
        assertEquals(1L, snap.stages[CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD])
        assertEquals(1L, snap.stages[CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS])
        assertEquals(7L, snap.droppedTotal)
        // received.* 不进 stages
        assertTrue(snap.stages.keys.none { it.startsWith(CaptureFunnelStats.RECEIVED_PREFIX) }, snap.stages.toString())
    }

    @Test
    @DisplayName("reset：取走上报后清零（不再重复上报）")
    fun resetClearsCounters() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_SMS)
        stats.record(CaptureFunnelStats.DROPPED_SMS_BLANK_TEXT)
        stats.reset()

        val snap = stats.snapshot()
        assertEquals(0L, snap.receivedTotal)
        assertTrue(snap.stages.isEmpty(), snap.stages.toString())
    }

    @Test
    @DisplayName("stage → 上报键机械映射，且全部落在 client_only.*（否则服务端 400）")
    fun stagesMapToReportableClientOnlyKeys() {
        assertEquals(
            "client_only.capture_dropped_a11y_no_root",
            CaptureFunnelStats.reportKey(CaptureFunnelStats.DROPPED_A11Y_NO_ROOT),
        )
        assertEquals(
            "client_only.capture_received_nls",
            CaptureFunnelStats.reportKey(CaptureFunnelStats.RECEIVED_NLS),
        )
        // 每个 stage 常量都能机械转成服务端接受的键
        val everyStage = listOf(
            CaptureFunnelStats.RECEIVED_NLS,
            CaptureFunnelStats.RECEIVED_A11Y,
            CaptureFunnelStats.RECEIVED_SMS,
            CaptureFunnelStats.DROPPED_NLS_NO_PACKAGE,
            CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS,
            CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED,
            CaptureFunnelStats.DROPPED_A11Y_NO_ROOT,
            CaptureFunnelStats.DROPPED_A11Y_ROOT_MISMATCH,
            CaptureFunnelStats.DROPPED_A11Y_NO_ACTIVITY,
            CaptureFunnelStats.DROPPED_A11Y_NOT_PAYMENT_ACTIVITY,
            CaptureFunnelStats.DROPPED_A11Y_NO_PAGE_TOKEN,
            CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD,
            CaptureFunnelStats.DROPPED_A11Y_EXCLUDED,
            CaptureFunnelStats.DROPPED_A11Y_BAD_AMOUNT,
            CaptureFunnelStats.DROPPED_A11Y_HISTORY_DATE,
            CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE,
            CaptureFunnelStats.DROPPED_A11Y_PAGE_DEDUP,
            CaptureFunnelStats.DROPPED_SMS_NOT_ACTION,
            CaptureFunnelStats.DROPPED_SMS_NO_MESSAGES,
            CaptureFunnelStats.DROPPED_SMS_BLANK_TEXT,
        )
        everyStage.forEach { stage ->
            val key = CaptureFunnelStats.reportKey(stage)
            assertTrue(PrefilterReport.isReportableRuleId(key), "$stage → $key 不在可上报命名空间")
            assertTrue(key.startsWith("client_only.capture_"), key)
            assertEquals(CaptureFunnelStats.channelOf(stage) != null, true, "stage 必须带渠道段：$stage")
        }
        // 缺渠道段的 key 不可归因（不上报）
        assertFalse(PrefilterReport.isReportableRuleId(CaptureFunnelStats.DROPPED_PREFIX + "bogus"))
    }

    @Test
    @DisplayName("reportCountsFor 按渠道归因，并把 received 也映射进 client_only.*")
    fun reportCountsAreAttributedPerChannel() {
        val stats = CaptureFunnelStatsImpl()
        repeat(3) { stats.record(CaptureFunnelStats.RECEIVED_A11Y) }
        stats.record(CaptureFunnelStats.DROPPED_A11Y_EXCLUDED)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_PACKAGE)

        val snap = stats.snapshot()
        assertEquals(
            mapOf(
                "client_only.capture_received_a11y" to 3L,
                "client_only.capture_dropped_a11y_excluded" to 1L,
            ),
            snap.reportCountsFor(SourceChannel.A11Y),
        )
        assertEquals(
            mapOf("client_only.capture_dropped_nls_no_package" to 1L),
            snap.reportCountsFor(SourceChannel.NLS),
        )
        assertTrue(snap.reportCountsFor(SourceChannel.SMS).isEmpty())
    }

    @Test
    @DisplayName("AI 响应侧 stage：按渠道归因、上报键 client_only.ai_extra_items、不计入捕获层 droppedTotal")
    fun aiResponseStageIsSeparateAndReportable() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)
        repeat(2) { stats.record(CaptureFunnelStats.aiExtraItemsStage(SourceChannel.NLS)) }

        val snap = stats.snapshot()
        assertEquals(
            mapOf(
                "client_only.capture_received_nls" to 1L,
                "client_only.capture_dropped_nls_no_extras" to 1L,
                "client_only.ai_extra_items" to 2L,
            ),
            snap.reportCountsFor(SourceChannel.NLS),
        )
        assertTrue(PrefilterReport.isReportableRuleId(CaptureFunnelStats.AI_EXTRA_ITEMS_REPORT_KEY))
        // 捕获层合计不含 AI 响应侧；AI 侧单独统计
        assertEquals(1L, snap.droppedTotal)
        assertEquals(2L, snap.aiResponseTotal)
    }

    @Test
    @DisplayName("AI 失败丢弃 stage：上报键 client_only.unparsed_ai_failed，不计入 droppedTotal")
    fun unparsedStageIsSeparateAndReportable() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)
        stats.record(CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS))
        repeat(2) { stats.record(CaptureFunnelStats.unparsedFailedStage(SourceChannel.SMS)) }

        val snap = stats.snapshot()
        val key = CaptureFunnelStats.reportKey(CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS))
        assertEquals("client_only.unparsed_ai_failed", key)
        assertTrue(PrefilterReport.isReportableRuleId(key))
        // 渠道归因仍然成立（第二段是渠道名），但上报键同一（渠道由请求 channel 承载）
        assertEquals(
            mapOf(
                "client_only.capture_received_nls" to 1L,
                "client_only.capture_dropped_nls_no_extras" to 1L,
                "client_only.unparsed_ai_failed" to 1L,
            ),
            snap.reportCountsFor(SourceChannel.NLS),
        )
        assertEquals(
            mapOf("client_only.unparsed_ai_failed" to 2L),
            snap.reportCountsFor(SourceChannel.SMS),
        )
        // 早退合计不含 AI 失败丢弃；失败丢弃单独统计；与 AI 响应侧互不干扰
        assertEquals(1L, snap.droppedTotal)
        assertEquals(3L, snap.unparsedTotal)
        assertEquals(0L, snap.aiResponseTotal)
    }

    @Test
    @DisplayName("渲染：AI 失败丢弃单独列一节，并在合计行报次数")
    fun renderSeparatesUnparsedStages() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)
        stats.record(CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS))

        val text = CaptureFunnelStats.render(stats.snapshot())
        assertTrue(text.contains("AI 失败丢弃"), text)
        assertTrue(text.contains("unparsed.nls.ai_failed"), text)
        assertTrue(text.contains("早退丢弃 1"), text)
        assertTrue(text.contains("AI 失败丢弃 1"), text)
    }

    @Test
    @DisplayName("渲染：捕获层与 AI 响应侧分开列出")
    fun renderSeparatesAiResponseStages() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)
        stats.record(CaptureFunnelStats.aiExtraItemsStage(SourceChannel.NLS))

        val text = CaptureFunnelStats.render(stats.snapshot())
        assertTrue(text.contains("AI 响应侧"), text)
        assertTrue(text.contains("ai.nls.extra_items"), text)
        assertTrue(text.contains("早退丢弃 1"), text)
        assertTrue(text.contains("AI 响应裁剪 1 次"), text)
    }

    @Test
    @DisplayName("渲染：列出三渠道入口，早退按条数降序，带人读标签")
    fun renderIsGroupedAndSorted() {
        val stats = CaptureFunnelStatsImpl()
        stats.record(CaptureFunnelStats.RECEIVED_A11Y)
        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        repeat(9) { stats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED) }
        repeat(2) { stats.record(CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD) }
        stats.record(CaptureFunnelStats.DROPPED_NLS_NO_EXTRAS)

        val text = CaptureFunnelStats.render(stats.snapshot())
        assertTrue(text.contains("入口:"), text)
        assertTrue(text.contains("A11Y 收到 1"), text)
        assertTrue(text.contains("SMS 收到 0"), text)
        assertTrue(text.contains("合计: 收到 2 · 早退丢弃 12"), text)
        // 9 条的排在 2 条的前面；2 条的排在 1 条的前面
        assertTrue(
            text.indexOf("not_whitelisted") < text.indexOf("no_success_keyword"),
            "应按条数降序：\n$text",
        )
        assertTrue(text.indexOf("no_success_keyword") < text.indexOf("no_extras"), "应按条数降序：\n$text")
        // 人读标签
        assertTrue(text.contains("包名不在支付白名单"), text)
        assertTrue(text.contains("未命中支付成功词"), text)
    }

    @Test
    @DisplayName("空快照渲染：明确「暂无捕获记录」而不是空段")
    fun renderEmptySnapshot() {
        val text = CaptureFunnelStats.render(CaptureFunnelStatsImpl().snapshot())
        assertTrue(text.contains("暂无捕获记录"), text)
    }

    @Test
    @DisplayName("三渠道并发计数不丢（ConcurrentHashMap + AtomicLong）")
    fun concurrentRecordDoesNotLoseCounts() {
        val stats = CaptureFunnelStatsImpl()
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map {
                executor.submit {
                    start.await()
                    repeat(perThread) {
                        stats.record(CaptureFunnelStats.RECEIVED_A11Y)
                        stats.record(CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val snap = stats.snapshot()
        assertEquals((threads * perThread).toLong(), snap.received.getValue(SourceChannel.A11Y))
        assertEquals((threads * perThread).toLong(), snap.stages[CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE])
    }
}
