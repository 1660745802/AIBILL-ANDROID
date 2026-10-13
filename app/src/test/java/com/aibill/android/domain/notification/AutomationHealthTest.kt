package com.aibill.android.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [AutomationHealth] 的纯谓词（P0-3 文案严谨性的护栏）：
 * 只有「通知监听没开」才允许说「自动记账当前不会工作」；其余缺失只能说「能力不完整」。
 */
class AutomationHealthTest {

    private fun health(
        nls: Boolean = true,
        a11y: Boolean = true,
        battery: Boolean = true,
    ) = AutomationHealth(
        notificationListenerEnabled = nls,
        accessibilityEnabled = a11y,
        batteryOptimizationIgnored = battery,
    )

    @Test
    @DisplayName("三项都开 → 不需要提示")
    fun allGranted() {
        val h = health()
        assertFalse(h.coreBroken)
        assertFalse(h.degraded)
        assertFalse(h.needsAttention)
        assertTrue(h.missing.isEmpty())
    }

    @Test
    @DisplayName("通知监听未开 → coreBroken（可断言「不会工作」）")
    fun notificationListenerOffIsCoreBroken() {
        val h = health(nls = false)
        assertTrue(h.coreBroken)
        assertTrue(h.needsAttention)
        assertEquals(listOf(AutomationHealth.Item.NOTIFICATION_LISTENER), h.missing)
    }

    @Test
    @DisplayName("只缺无障碍 / 电池 → degraded（不得说「不会工作」）")
    fun partialMissingIsDegradedNotBroken() {
        val onlyA11y = health(a11y = false)
        assertFalse(onlyA11y.coreBroken, "无障碍缺失不等于整体不工作")
        assertTrue(onlyA11y.degraded)
        assertTrue(onlyA11y.needsAttention)

        val onlyBattery = health(battery = false)
        assertFalse(onlyBattery.coreBroken)
        assertTrue(onlyBattery.degraded)
    }

    @Test
    @DisplayName("missing 只列缺失项且顺序稳定（NLS → 无障碍 → 电池）")
    fun missingIsOrdered() {
        assertEquals(
            listOf(
                AutomationHealth.Item.NOTIFICATION_LISTENER,
                AutomationHealth.Item.ACCESSIBILITY,
                AutomationHealth.Item.BATTERY_OPTIMIZATION,
            ),
            health(nls = false, a11y = false, battery = false).missing,
        )
        assertEquals(
            listOf(
                AutomationHealth.Item.ACCESSIBILITY,
                AutomationHealth.Item.BATTERY_OPTIMIZATION,
            ),
            health(a11y = false, battery = false).missing,
        )
    }
}
