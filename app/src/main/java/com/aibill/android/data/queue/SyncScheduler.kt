package com.aibill.android.data.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.aibill.android.service.SyncWorker
import java.util.concurrent.TimeUnit

/**
 * 排同步的 WorkManager 管道（离线队列 module 的**内部**实现）。
 *
 * ⚠️ **唯一生产调用方是 [SyncQueueImpl]**：收口前本文件有 6 个调用点
 * （入账 module / `TransactionRepository.syncPending` / `WorkManagerProvider` /
 * `NetworkMonitor` …），每个调用方各自 try/catch、各自理解「已入队 ≠ 已上传」。
 * 现在「排调度」是 [SyncQueue.requestSync] 这一个入口的内部细节。
 *
 * 要排同步请调 [SyncQueue.requestSync]，不要直接调本文件：
 * 本文件抛出的异常已经被 [SyncQueueImpl] 翻译成 [SyncRequest.Rejected]，
 * 直接调会绕过「排调度失败绝不打断入账」这条不变量。
 */
object SyncScheduler {

    fun scheduleSyncIfNeeded(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                SyncWorker.WORK_NAME,
                // APPEND_OR_REPLACE：上次 worker 任意状态都允许新 worker 排上队，
                // 避免 KEEP 导致"上次成功 → 新 pending 数据永远等不到下次同步"
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
            )
    }
}
