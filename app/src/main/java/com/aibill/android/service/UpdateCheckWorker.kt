package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.util.UpdateNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 每日检查 APK 更新（GitHub Release）。
 * 有新版本时发通知提示用户（温和，不强制）。
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val updateManager: UpdateManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val info = updateManager.checkUpdate()
            if (info != null) {
                UpdateNotifier.showUpdateNotification(context, info)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "Update check failed")
            // P1-1：transient 错误（网络 / 临时不可用）应 retry，避免下个周期才
            // 重试造成不必要的 24h 延迟。WorkManager 默认指数退避（10s 起，上限 5h）。
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                1, TimeUnit.DAYS
            )
                // PR 修复：检查 GitHub Release 必须联网，离线时跳过本周期
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
