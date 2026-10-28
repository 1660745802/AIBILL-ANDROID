package com.aibill.android.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [A11yAlertThrottle.shouldNotify] 纯谓词的护栏：
 * 心跳巡检在持续故障下**不允许**每 6 小时重复骚扰用户，
 * 但故障类型变化 / 间隔到期 / 服务恢复后再断开必须立即提醒。
 */
class A11yAlertThrottleTest {

    private val hour = 60 * 60 * 1000L

    @Test
    @DisplayName("从未提醒过 → 立即提醒")
    fun firstTimeNotifies() {
        assertTrue(
            A11yAlertThrottle.shouldNotify(
                state = A11yAlertThrottle.STATE_DISCONNECTED,
                lastState = 0,
                lastNotifiedAt = 0L,
                now = 1_000L,
            )
        )
    }

    @Test
    @DisplayName("同类故障 24h 内已提醒过 → 不重复骚扰")
    fun sameStateWithinIntervalIsSuppressed() {
        assertFalse(
            A11yAlertThrottle.shouldNotify(
                state = A11yAlertThrottle.STATE_DISCONNECTED,
                lastState = A11yAlertThrottle.STATE_DISCONNECTED,
                lastNotifiedAt = 1_000L,
                now = 1_000L + 23 * hour,
            )
        )
    }

    @Test
    @DisplayName("同类故障满 24h → 允许再次提醒")
    fun sameStateAfterIntervalNotifies() {
        assertTrue(
            A11yAlertThrottle.shouldNotify(
                state = A11yAlertThrottle.STATE_DISCONNECTED,
                lastState = A11yAlertThrottle.STATE_DISCONNECTED,
                lastNotifiedAt = 1_000L,
                now = 1_000L + 24 * hour,
            )
        )
    }

    @Test
    @DisplayName("故障类型变了（断开 → 开关被关）→ 即使刚提醒过也立即提醒")
    fun stateChangeNotifiesImmediately() {
        assertTrue(
            A11yAlertThrottle.shouldNotify(
                state = A11yAlertThrottle.STATE_TOGGLE_OFF,
                lastState = A11yAlertThrottle.STATE_DISCONNECTED,
                lastNotifiedAt = 1_000L,
                now = 1_000L + 1,
            )
        )
    }

    @Test
    @DisplayName("服务恢复（clear 后时间戳清零）→ 下次真故障立即提醒")
    fun clearedStateNotifiesImmediately() {
        // clear() 把 last_notified_at 归零，等价于 lastNotifiedAt = 0
        assertTrue(
            A11yAlertThrottle.shouldNotify(
                state = A11yAlertThrottle.STATE_TOGGLE_OFF,
                lastState = A11yAlertThrottle.STATE_TOGGLE_OFF,
                lastNotifiedAt = 0L,
                now = 10_000L,
            )
        )
    }
}
