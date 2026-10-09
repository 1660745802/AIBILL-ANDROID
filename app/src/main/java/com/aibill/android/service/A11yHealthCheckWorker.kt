package com.aibill.android.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.util.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
 */
@HiltWorker
class A11yHealthCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val toggleOn = isA11yServiceEnabled()

        if (!toggleOn) {
            Timber.w("A11y 心跳: 无障碍开关已关闭，发提醒")
            NotificationHelper.showA11yDisconnectedNotification(context, connected = false)
            return Result.success()
        }

        // 开关开着但服务未连接。注意进程被杀时该静态值会随进程消失，
        // 所以读到的 false 也可能是「进程刚起还没收到事件」；
        // 宁可真阳性（多发一次提醒）也不误报（漏报 = 记账静默失效）。
        if (!PaymentAccessibilityService.isServiceConnected) {
            Timber.w("A11y 心跳: 开关已开但服务未连接（可能已被系统断开）")
            NotificationHelper.showA11yDisconnectedNotification(context, connected = true)
        }
        return Result.success()
    }

    private fun isA11yServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    companion object {
        const val WORK_NAME = "a11y_health_check"

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
