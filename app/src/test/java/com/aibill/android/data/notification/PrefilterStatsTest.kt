package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

/**
 * 预筛拦截计数（PRODUCT §三的第一缺口：预筛拦下了什么没有计数）。
 *
 * 三个层面：
 * - [PrefilterStatsImpl] 自身的计数与渲染（按渠道分桶，键是契约命名空间字符串）；
 * - **接线**：真实的 [NotificationPrefilterImpl] 拦截/放行时确实记了数
 *   （否则计数只是一份没人写的数据）；
 * - 窗口：[drain] 返回后清零（⑤ 上报依赖它）。
 */
class PrefilterStatsTest {

    private fun capture(text: String) = Capture(
        channel = SourceChannel.NLS,
        packageName = "com.tencent.mm",
        title = "微信支付",
        fullText = text,
        receivedAt = 1_000_000L,
    )

    @Test
    @DisplayName("按渠道 + 契约命名空间的键分档计数，放行单独计数，拦截率可算")
    fun countsByChannelAndKey() {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, PrefilterStats.clientOnlyKey(BlockReason.MARKETING))
        stats.recordBlocked(SourceChannel.NLS, PrefilterStats.clientOnlyKey(BlockReason.MARKETING))
        stats.recordBlocked(SourceChannel.NLS, PrefilterStats.clientOnlyKey(BlockReason.DUPLICATE_CONTENT))
        stats.recordBlocked(SourceChannel.SMS, "precheck.money_only")
        stats.recordPassed(SourceChannel.NLS)

        val snap = stats.snapshot()
        val nls = snap.channels.getValue(SourceChannel.NLS)
        assertEquals(2L, nls.blocked["client_only.MARKETING"])
        assertEquals(1L, nls.blocked["client_only.DUPLICATE_CONTENT"])
        assertEquals(1L, nls.passed)
        assertEquals(3L, nls.blockedTotal)
        assertEquals(75.0, nls.blockRatePercent, 0.01)
        // 渠道各算各的
        assertEquals(1L, snap.channels.getValue(SourceChannel.SMS).blocked["precheck.money_only"])
        assertEquals(4L, snap.blockedTotal)
        assertEquals(1L, snap.passed)
        assertEquals(80.0, snap.blockRatePercent, 0.01)
    }

    @Test
    @DisplayName("drain：返回当前窗口并清零（窗口起点推进），供 ⑤ 上报")
    fun drainResetsWindow() {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, "precheck.strong_noise")
        stats.recordPassed(SourceChannel.NLS)

        val window = stats.drain(now = 5_000L)
        assertEquals(1L, window.channels.getValue(SourceChannel.NLS).blockedTotal)
        assertEquals(1L, window.passed)
        assertEquals(5_000L, window.windowEnd)

        // 清零后新窗口为空（不会把上一窗口重复上报）
        assertTrue(stats.snapshot().channels.isEmpty(), "drain 后应清零")
    }

    @Test
    @DisplayName("渲染按渠道分组、组内按条数降序，带人读标签")
    fun renderIsGroupedAndSorted() {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.NLS, PrefilterStats.clientOnlyKey(BlockReason.MARKETING))
        repeat(5) { stats.recordBlocked(SourceChannel.NLS, PrefilterStats.clientOnlyKey(BlockReason.DUPLICATE_CONTENT)) }
        stats.recordBlocked(SourceChannel.SMS, "precheck.weak_noise")
        stats.recordPassed(SourceChannel.NLS)

        val text = PrefilterStats.render(stats.snapshot())
        assertTrue(text.contains("放行 1 · 拦截 6"), text)
        // 5 条的在 1 条的前面
        assertTrue(
            text.indexOf("DUPLICATE_CONTENT") < text.indexOf("MARKETING"),
            "应按条数降序排列：\n$text",
        )
        // 带人读标签
        assertTrue(text.contains("内存去重(5s)"), text)
        assertTrue(text.contains("SMS"), text)
    }

    @Test
    @DisplayName("接线：真实预筛拦截时，按 client_only.<reason> 计数")
    fun prefilterRecordsBlockedCounts() = runTest {
        val stats = PrefilterStatsImpl()
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(
            // 显式给词表，避免依赖 harness 默认（默认 spamKeywords 为空）
            source = InMemoryNotificationRuleSource(
                PrefilterTestHarness.rules(spamKeywords = listOf("优惠券"))
            ),
            stats = stats,
        )

        val verdict = prefilter.evaluate(capture("点击领取优惠券，全场5折，速来抢购"))
        assertTrue(verdict is Verdict.Block, "应当被拦：$verdict")

        val snap = stats.snapshot()
        assertEquals(1L, snap.blockedTotal, "拦截应被计数：$snap")
        assertEquals(
            1L,
            snap.channels.getValue(SourceChannel.NLS).blocked[PrefilterStats.clientOnlyKey(BlockReason.MARKETING)],
            "客户端自有判据按 client_only.* 计数：$snap",
        )
        assertEquals(0L, snap.passed)
    }

    @Test
    @DisplayName("接线：放行的通知计入 passed（拦截率才可算）")
    fun prefilterRecordsPassedCounts() = runTest {
        val stats = PrefilterStatsImpl()
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(
            source = InMemoryNotificationRuleSource(PrefilterTestHarness.rules()),
            stats = stats,
        )

        val verdict = prefilter.evaluate(capture("微信支付 你有一笔20.00元的支出"))
        assertTrue(verdict is Verdict.Pass, "应当放行：$verdict")

        val snap = stats.snapshot()
        assertEquals(1L, snap.passed)
        assertEquals(0L, snap.blockedTotal)
    }
}
