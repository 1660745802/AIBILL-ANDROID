package com.aibill.android.data.queue

import android.content.Context
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.queue.SyncQueue
import com.aibill.android.domain.queue.SyncRequest
import com.aibill.android.service.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线队列 module 的实现。
 *
 * 它是 [PendingTransactionDao] 的**唯一**业务调用方，也是 [SyncScheduler] 的
 * **唯一**生产调用方（排调度从此只有一个入口）。
 *
 * ## 不变量
 *
 * - 快照只走 [PendingTransactionDao.observeSyncStatus]（一条聚合查询，两个分区同源）；
 * - 清空警告只走 [PendingTransactionDao.getAnyUnsyncedCount]（pending + failed）；
 * - 401 恢复只走 [PendingTransactionDao.resetFailedMatching]，判据与写入侧
 *   [SyncWorker.ERROR_TOKEN_EXPIRED] 配对（常量在 SyncWorker，两边都用同一对）。
 */
@Singleton
class SyncQueueImpl @Inject constructor(
    private val dao: PendingTransactionDao,
    @ApplicationContext private val context: Context,
) : SyncQueue {

    override fun observeStatus(): Flow<SyncQueueStatus> = dao.observeSyncStatus()

    override suspend fun unsyncedCount(): Int = dao.getAnyUnsyncedCount()

    override suspend fun clear() = dao.deleteAll()

    override suspend fun recoverTokenExpiredFailures(): Int =
        dao.resetFailedMatching(errorPattern = SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN)

    override fun requestSync(): SyncRequest = try {
        SyncScheduler.scheduleSyncIfNeeded(context)
        SyncRequest.Enqueued
    } catch (e: Exception) {
        // 不抛给调用方：入账侧抛出去会崩进程（无全局 CoroutineExceptionHandler），
        // 首页侧抛出去只是让 WorkManager 不可用这件事无处可报。统一在这里记日志、
        // 以 Rejected 返回，由调用方决定要不要告诉用户。
        Timber.w(e, "排同步失败：WorkManager 未接受入队（队列数据未受影响）")
        SyncRequest.Rejected(e)
    }
}
