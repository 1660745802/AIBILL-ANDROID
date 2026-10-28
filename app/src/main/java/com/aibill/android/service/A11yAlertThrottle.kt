package com.aibill.android.service

import android.content.Context
import android.content.SharedPreferences

/**
 * 无障碍断连告警的**节流状态**（落盘，进程无关）。
 *
 * 背景： [A11yHealthCheckWorker] 每 6 小时巡检一次，故障若持续存在，不加节流
 * 就会每 6 小时重复弹一条 Heads-up「无障碍已断开」——用户被反复骚扰。
 * 本类保证：**同一种故障在 [REPEAT_INTERVAL_MS] 内只提醒一次**；
 * 故障类型变了（开关被关 vs 开关开着但服务没连上）立即提醒，
 * 因为那往往意味着故障升级/变化，用户需要知道。
 *
 * 服务恢复（[PaymentAccessibilityService.onServiceConnected] 或巡检判定健康）时
 * 必须调 [clear]：一是撤掉残留通知，二是让**下一次真故障立刻提醒**，
 * 不被上一轮的节流时间戳拖累。
 */
object A11yAlertThrottle {

    /** 无障碍开关被关。 */
    const val STATE_TOGGLE_OFF = 1

    /** 开关开着但服务没连上（国内 ROM 后台断开的典型症状）。 */
    const val STATE_DISCONNECTED = 2

    /** 同一故障的重复提醒间隔：24 小时。 */
    const val REPEAT_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    private const val PREFS_NAME = "a11y_alert_throttle"
    private const val KEY_LAST_STATE = "last_state"
    private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"

    /**
     * 纯谓词，判定此刻是否应该发提醒。
     *
     * @param state          本次故障类型（[STATE_TOGGLE_OFF] / [STATE_DISCONNECTED]）
     * @param lastState      上次提醒时的故障类型；0 = 从未提醒过
     * @param lastNotifiedAt 上次提醒的时间戳；<=0 = 从未提醒过
     * @param now            当前时间戳
     */
    fun shouldNotify(
        state: Int,
        lastState: Int,
        lastNotifiedAt: Long,
        now: Long,
        repeatIntervalMs: Long = REPEAT_INTERVAL_MS,
    ): Boolean {
        if (lastNotifiedAt <= 0L) return true          // 从未提醒过
        if (state != lastState) return true            // 故障类型变了，立即提醒
        return now - lastNotifiedAt >= repeatIntervalMs // 同类故障，间隔够了才重复
    }

    /** [shouldNotify] 的持久化入口：状态读自 SharedPreferences。 */
    fun shouldNotify(context: Context, state: Int, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        return shouldNotify(
            state = state,
            lastState = prefs.getInt(KEY_LAST_STATE, 0),
            lastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L),
            now = now,
        )
    }

    /** 通知发出后调用，记录本轮故障类型与时间戳。 */
    fun markNotified(context: Context, state: Int, now: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putInt(KEY_LAST_STATE, state)
            .putLong(KEY_LAST_NOTIFIED_AT, now)
            .apply()
    }

    /** 服务恢复健康后调用：清空节流状态，下一次真故障立即提醒。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
