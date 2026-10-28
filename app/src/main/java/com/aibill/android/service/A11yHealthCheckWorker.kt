package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.util.BatteryOptimizationHelper
import com.aibill.android.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务存活检测。
 * 每 6 小时检查一次，分两种故障处理：
 *
 * 1. **开关被关** —— 无障碍权限被用户在系统设置里移除。
 * 2. **开关开着但服务没连上** —— 国内 ROM 后台杀进程/断开连接的典型症状。
 *
 * 两种都只能发通知引导用户手动处理，**没有自动恢复手段**：
 * NLS 有 `NotificationListenerService.requestRebind()` 可自动重连，
 * 而无障碍侧 Android 未开放对应 API——
 * 写 `Settings.Secure.enabled_accessibility_services` 需要
 * `WRITE_SECURE_SETTINGS`（系统签名级权限，普通 App 申请不到），
 * `AccessibilityService` 本身也没有公开的 reconnect 方法。
 *
 * 本 Worker 的真实价值是**把「开着但没连上」区分出来并告知用户**：
 * 之前只看设置开关，这种故障完全检测不到，用户以为开着、实际自动记账
 * 已经静默失效很久了。
 *
 * ⚠️ 两条防误报/防骚扰护栏（缺一都会导致「老是弹重连通知」）：
 * 1. **延迟复核** —— WorkManager 定期唤醒常冷启动进程，此刻
 *    [PaymentAccessibilityService.isServiceConnected] 必然先为 false（服务要等
 *    系统 rebind 才置 true），立刻判定会把「还没连上」误报成「已被断开」。
 *    所以先等 [RECHECK_DELAY_MS] 再复查一次，给 rebind 留时间。
 * 2. **告警节流** —— 持续故障下同种告警 24h 内只发一次（[A11yAlertThrottle]），
 *    健康时清节流并撤掉残留通知。
 */
@HiltWorker
class A11yHealthCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val toggleOn = isA11yServiceEnabled()

        if (!toggleOn) {
            Timber.w("A11y 心跳: 无障碍开关已关闭")
            maybeNotify(A11yAlertThrottle.STATE_TOGGLE_OFF)
            return Result.success()
        }

        if (PaymentAccessibilityService.isServiceConnected) {
            markHealthy()
            return Result.success()
        }

        // 开关开着但此刻服务没连上——先别下结论。进程冷启动时（WorkManager
        // 定期唤醒常如此）该静态值必然先是 false，服务要等系统 rebind 才置 true。
        // 等一会复查，让 rebind 有机会完成，避免把「还没连上」误报成「已被断开」。
        delay(RECHECK_DELAY_MS)
        if (PaymentAccessibilityService.isServiceConnected) {
            markHealthy()
            return Result.success()
        }

        Timber.w("A11y 心跳: 开关已开但服务未连接（复查后仍未连上，可能已被系统断开）")
        maybeNotify(A11yAlertThrottle.STATE_DISCONNECTED)
        return Result.success()
    }

    /** 判定健康：撤掉残留的「已断开」通知，并清节流让下次真故障立即提醒。 */
    private fun markHealthy() {
        NotificationHelper.cancelA11yDisconnectedNotification(context)
        A11yAlertThrottle.clear(context)
    }

    /** 节流后的提醒出口：同种故障 24h 内只发一次。 */
    private fun maybeNotify(state: Int) {
        if (!A11yAlertThrottle.shouldNotify(context, state)) {
            Timber.d("A11y 心跳: 同类告警 24h 内已提醒过，跳过")
            return
        }
        NotificationHelper.showA11yDisconnectedNotification(
            context,
            connected = state == A11yAlertThrottle.STATE_DISCONNECTED,
        )
        A11yAlertThrottle.markNotified(context, state)
    }

    /** 与引导卡片 / 权限页同一判据（[BatteryOptimizationHelper.isA11yServiceEnabled]）。 */
    private fun isA11yServiceEnabled(): Boolean =
        BatteryOptimizationHelper.isA11yServiceEnabled(context)

    companion object {
        const val WORK_NAME = "a11y_health_check"

        /** 复查等待时长：给系统 rebind 无障碍服务留时间。 */
        const val RECHECK_DELAY_MS = 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<A11yHealthCheckWorker>(
                6, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
