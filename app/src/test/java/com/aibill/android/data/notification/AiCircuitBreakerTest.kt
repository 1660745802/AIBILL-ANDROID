package com.aibill.android.data.notification

import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [AiCircuitBreaker] 的接口级测试：直接打 interface，时间用 [AiCircuitBreaker.recordOutcome]
 * / [AiCircuitBreaker.isMuted] 的 `now` 参数推进（该参数存在的唯一理由就是让窗口边界可钉）。
 *
 * 覆盖：阈值、静音窗口边界、成功清零、按来源隔离、未达阈值、未知来源。
 */
class AiCircuitBreakerTest {

    private fun newBreaker(): AiCircuitBreakerImpl =
        AiCircuitBreakerImpl(AppLogger(mockk(relaxed = true)))

    private val threshold = 3
    private val cooldownMs = 5 * 60_000L

    @Test
    @DisplayName("连续失败达到阈值后该来源静音")
    fun `连续失败达到阈值后静音`() {
        val breaker = newBreaker()
        assertFalse(breaker.isMuted("tv.danmaku.bili", now = 1_000L))
        repeat(threshold) { breaker.recordOutcome("tv.danmaku.bili", success = false, now = 1_000L) }
        assertTrue(breaker.isMuted("tv.danmaku.bili", now = 1_001L))
    }

    @Test
    @DisplayName("静音窗口边界：到期前一瞬静音，到期即恢复")
    fun `静音窗口边界`() {
        val breaker = newBreaker()
        val failedAt = 1_000L
        repeat(threshold) { breaker.recordOutcome("pkg", success = false, now = failedAt) }
        val mutedUntil = failedAt + cooldownMs
        assertTrue(breaker.isMuted("pkg", now = mutedUntil - 1), "到期前应静音")
        assertFalse(breaker.isMuted("pkg", now = mutedUntil), "到期即恢复，避免一次抖动永久静音")
    }

    @Test
    @DisplayName("成功一次立即清除该来源的熔断状态")
    fun `成功清零`() {
        val breaker = newBreaker()
        repeat(threshold) { breaker.recordOutcome("pkg", success = false, now = 1_000L) }
        assertTrue(breaker.isMuted("pkg", now = 2_000L))
        breaker.recordOutcome("pkg", success = true, now = 3_000L)
        assertFalse(breaker.isMuted("pkg", now = 4_000L))
    }

    @Test
    @DisplayName("熔断按来源隔离，互不影响")
    fun `按来源隔离`() {
        val breaker = newBreaker()
        repeat(threshold) { breaker.recordOutcome("tv.danmaku.bili", success = false, now = 1_000L) }
        assertTrue(breaker.isMuted("tv.danmaku.bili", now = 2_000L))
        assertFalse(breaker.isMuted("com.netease.yanxuan", now = 2_000L))
    }

    @Test
    @DisplayName("失败未达阈值不静音")
    fun `未达阈值不静音`() {
        val breaker = newBreaker()
        repeat(threshold - 1) { breaker.recordOutcome("pkg", success = false, now = 1_000L) }
        assertFalse(breaker.isMuted("pkg", now = 2_000L))
    }

    @Test
    @DisplayName("未知来源默认不静音")
    fun `未知来源不熔断`() {
        assertFalse(newBreaker().isMuted("com.never.seen.app", now = 1_000L))
    }
}
