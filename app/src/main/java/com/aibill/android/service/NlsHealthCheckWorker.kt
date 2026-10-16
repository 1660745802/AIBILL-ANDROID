package com.aibill.android.service

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.delay
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.util.BatteryOptimizationHelper
import com.aibill.android.util.NotificationHelper
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * NLS 健康度心跳 Worker
 *
 * 每 6 小时检查一次通知监听服务（NotificationListenerService）的状态，分三种情况：
 *
 * 1. **权限被关** —— 通知监听被用户在系统设置里移除；
 * 2. **权限在、服务没连上** —— 国内 ROM 杀进程后的典型形态，`requestRebind` 可自愈；
 * 3. 都正常 —— 不做任何事。
 *
 * 2 与 3 的区分靠 `NotificationMonitorService.isConnected`（运行期事实），
 * **不能只看 `Settings.Secure.enabled_notification_listeners`**（那只是开关）。
 * 改动前只看开关，于是第 2 种被当成「正常」→ 既不自动恢复也不提醒用户，
 * 表现为「通知经常监听不到但没人告诉我」。
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
        // 三态，而不是两态。改动前只有一个判据（Settings.Secure 里的开关），
        // 于是「开关开着但服务已断」→ 返回 true → **既不 requestRebind 也不提醒**，
        // 而那恰恰是 ROM 杀进程后最常见的形态。UI 那边（PermissionGuideScreen）
        // 一直用的是「开关 && isConnected」双态，只有后台漏了。
        val granted = isListenerGranted()
        if (!granted) {
            Timber.w("NLS 心跳: 通知监听权限已被关闭，发提醒")
            NotificationHelper.showNlsDisconnectedNotification(applicationContext)
            return Result.success()
        }

        if (NotificationMonitorService.isConnected) {
            Timber.d("NLS 心跳: 已连接")
            return Result.success()
        }

        // 权限在、服务没连上 → 先自愈。进程刚被拉起时 isConnected 可能还没来得及置位，
        // 所以宁可多走一次 requestRebind（幂等、无副作用），也不误报。
        try {
            val component = ComponentName(
                applicationContext.packageName,
                NotificationMonitorService::class.java.name
            )
            android.service.notification.NotificationListenerService.requestRebind(component)
            Timber.d("NLS 心跳: 已请求 requestRebind 自动恢复")
            // requestRebind 是异步的，等一下再判断，避免系统实际 rebind 成功却误报「断连」。
            delay(3_000)
            if (NotificationMonitorService.isConnected) {
                Timber.d("NLS 心跳: requestRebind 后已恢复，跳过通知")
                return Result.success()
            }
        } catch (e: Exception) {
            Timber.w(e, "NLS 心跳: requestRebind 失败")
        }
        // 恢复失败的话发通知提醒用户手动操作
        NotificationHelper.showNlsDisconnectedNotification(applicationContext)
        return Result.success()
    }

    /**
     * 「通知监听权限开关是否打开」——**只读开关，不代表服务已连上**。
     *
     * 判据收敛到 [BatteryOptimizationHelper.isNotificationListenerEnabled]（与权限引导页、
     * 首页健康卡同一份），不再在本类重写一遍 `Settings.Secure` 读法。
     */
    private fun isListenerGranted(): Boolean =
        BatteryOptimizationHelper.isNotificationListenerEnabled(applicationContext)
}
