package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
            Result.success() // 不 retry，等下个周期
        }
    }

    companion object {
        const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                1, TimeUnit.DAYS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
