package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 规则同步 Worker
 *
 * 每 6 小时从服务端拉取最新通知记账规则。
 * 支持 ETag/304，版本未变不会重复下载。
 */
@HiltWorker
class RulesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rulesManager: NotificationRulesManager,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "rules_sync"
        private const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RulesSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            rulesManager.fetchRules()
            Timber.d("Rules sync completed")
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "Rules sync failed, will retry next cycle")
            Result.success() // 不 retry，等下个周期
        }
    }
}
