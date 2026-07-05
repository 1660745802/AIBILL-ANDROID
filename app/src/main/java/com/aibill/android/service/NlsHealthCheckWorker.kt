package com.aibill.android.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.util.NotificationHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * NLS 健康度心跳 Worker
 *
 * 每 6 小时检查一次通知监听服务（NotificationListenerService）的连接状态。
 * 如果发现 NLS 被系统杀死或权限被撤销，发送前台通知提醒用户修复。
 *
 * 场景：国内 ROM 经常后台杀进程，NLS 断连后用户无感知，
 * 导致自动记账静默失效。心跳检测能及时提醒。
 */
class NlsHealthCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "nls_health_check"
        private const val INTERVAL_HOURS = 6L

        /**
         * 注册周期性心跳检查
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NlsHealthCheckWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Timber.d("NLS 心跳 Worker 已注册（每 ${INTERVAL_HOURS}h）")
        }
    }

    override suspend fun doWork(): Result {
        val isConnected = isNlsConnected()
        Timber.d("NLS 心跳检查: connected=$isConnected")

        if (!isConnected) {
            // NLS 断连，发送通知提醒用户
            NotificationHelper.showNlsDisconnectedNotification(applicationContext)
        }

        return Result.success()
    }

    /**
     * 检查 NLS 是否已连接（系统权限 + 服务存活）
     */
    private fun isNlsConnected(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        val componentName = ComponentName(
            applicationContext.packageName,
            NotificationMonitorService::class.java.name
        ).flattenToString()

        return enabledListeners.contains(componentName)
    }
}
