package com.aibill.android.service

import com.aibill.android.data.notification.rules.NotificationRulesManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.domain.notification.PrefilterReport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 规则同步 Worker
 *
 * 每 6 小时从服务端拉取最新通知记账规则（ETag/304）。
 *
 * 同时承担 ⑤ 拦截计数上报（跨端契约 §4）：同一个后台任务，**不用前台定时器**；
 * 上报是 best-effort，失败只记日志、下个窗口重发。
 */
@HiltWorker
class RulesSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rulesManager: NotificationRulesManager,
    private val prefilterReport: PrefilterReport,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "rules_sync"
        private const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RulesSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                // PR 修复：拉取服务端规则需要联网，离线时直接跳过本周期
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

    override suspend fun doWork(): Result {
        try {
            rulesManager.fetchRules()
            Timber.d("Rules sync completed")
        } catch (e: Exception) {
            Timber.w(e, "Rules sync failed, will retry next cycle")
            // 不 retry，等下个周期
        }

        // ⑤ 上报在规则同步之外：两者独立 best-effort（规则同步失败不应吞掉计数上报）。
        try {
            prefilterReport.report(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Filter report failed, kept for next cycle")
        }
        return Result.success()
    }
}
