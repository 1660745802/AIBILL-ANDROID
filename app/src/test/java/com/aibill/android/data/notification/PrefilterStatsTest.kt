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
 * 预筛拦截计数（PRODUCT §三 的第一缺口：预筛拦下了什么没有计数）。
 *
 * 两个层面：
 * - [PrefilterStatsImpl] 自身的计数与渲染；
 * - **接线**：真实的 [NotificationPrefilterImpl] 拦截/放行时确实记了数
 *   （否则计数只是一份没人写的数据）。
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
    @DisplayName("按原因分档计数，放行单独计数，拦截率可算")
    fun countsByReasonAndPassed() {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(BlockReason.MARKETING)
        stats.recordBlocked(BlockReason.MARKETING)
        stats.recordBlocked(BlockReason.DUPLICATE_CONTENT)
        stats.recordPassed()

        val snap = stats.snapshot()
        assertEquals(2L, snap.blocked[BlockReason.MARKETING])
        assertEquals(1L, snap.blocked[BlockReason.DUPLICATE_CONTENT])
        assertEquals(1L, snap.passed)
        assertEquals(3L, snap.blockedTotal)
        assertEquals(75.0, snap.blockRatePercent, 0.01)
    }

    @Test
    @DisplayName("渲染按条数降序：一眼看出最常被拦的是什么")
    fun renderIsSortedByCountDesc() {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(BlockReason.MARKETING)
        repeat(5) { stats.recordBlocked(BlockReason.DUPLICATE_CONTENT) }
        stats.recordPassed()

        val text = PrefilterStats.render(stats.snapshot())
        assertTrue(text.contains("放行 1 · 拦截 6"), text)
        // 5 条的在 1 条的前面
        assertTrue(
            text.indexOf("DUPLICATE_CONTENT") < text.indexOf("MARKETING"),
            "应按条数降序排列：\n$text",
        )
        // 带人读标签
        assertTrue(text.contains("内存去重(5s)"), text)
    }

    @Test
    @DisplayName("接线：真实预筛拦截时，计数确实增加")
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
