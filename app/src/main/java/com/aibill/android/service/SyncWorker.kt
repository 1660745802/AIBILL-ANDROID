package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingTransactionDao: PendingTransactionDao,
    private val transactionApi: TransactionApi,
    private val syncLock: SyncLock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // PR C1：置 sync lock，AuthRepositoryImpl 换号前会 wait 直到 release
        syncLock.acquire()
        try {
            val pendingList = pendingTransactionDao.getAllPending()
            if (pendingList.isEmpty()) return Result.success()

            var unauthorizedSeen = false

            for (entity in pendingList) {
                if (entity.retryCount >= MAX_RETRY_COUNT) {
                    markFailed(entity.clientId, "Max retry count exceeded")
                    continue
                }

                val (result, serverId) = syncTransaction(entity)
                when (result) {
                    SyncResult.SUCCESS -> markSynced(entity.clientId, serverId)
                    SyncResult.UNAUTHORIZED -> {
                        // Token 过期：标记当前记录失败，避免下次 worker 重复触发 401 循环；
                        // AuthEventBus 已被 AuthInterceptor emit，由 MainActivity 触发跳登录
                        markFailed(entity.clientId, "Token expired, need re-login")
                        unauthorizedSeen = true
                    }
                    SyncResult.BUSINESS_ERROR -> markFailed(entity.clientId, "Business error")
                    SyncResult.NETWORK_ERROR -> incrementRetry(entity.clientId, "Network error")
                }
            }

            // 检查剩余 pending（含 failed 状态）数，如果还有任何未同步的，则 retry
            val remainingCount = pendingTransactionDao.getAnyUnsyncedCount()
            return when {
                unauthorizedSeen -> Result.failure()   // 等用户重新登录后再调度
                remainingCount > 0 -> Result.retry()
                else -> Result.success()
            }
        } finally {
            // PR C1：无论成功/异常/重试，必须释放锁，否则后续 login 会死等
            syncLock.release()
        }
    }

    /**
     * 同步单条 pending 交易，返回 (结果, 服务端 id)。
     * 服务端 id 来自 create 接口的 created[] 列表（按 client_id 匹配）。
     */
    private suspend fun syncTransaction(entity: PendingTransactionEntity): Pair<SyncResult, Int?> {
        return try {
            val request = CreateTransactionRequest(
                items = listOf(entity.toItemRequest())
            )
            val apiResponse = transactionApi.createTransactions(request)
            val data = apiResponse.data
            // 在 created 或 duplicates 中按 client_id 查找对应记录
            val matched = data?.created?.firstOrNull { it.clientId == entity.clientId }
                ?: data?.duplicates?.firstOrNull { it.clientId == entity.clientId }
            SyncResult.SUCCESS to matched?.id
        } catch (e: HttpException) {
            when (e.code()) {
                HTTP_UNAUTHORIZED -> SyncResult.UNAUTHORIZED to null
                else -> SyncResult.BUSINESS_ERROR to null
            }
        } catch (_: IOException) {
            SyncResult.NETWORK_ERROR to null
        }
    }

    /**
     * 标记同步成功并写入服务端返回的 transaction id，便于后续编辑/删除追溯
     */
    private suspend fun markSynced(clientId: String, serverId: Int?) {
        if (serverId != null) {
            pendingTransactionDao.markSynced(
                clientId = clientId,
                serverId = serverId,
                status = STATUS_SYNCED,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            pendingTransactionDao.updateSyncStatus(
                clientId = clientId,
                status = STATUS_SYNCED,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun markFailed(clientId: String, error: String) {
        pendingTransactionDao.updateSyncStatus(
            clientId = clientId,
            status = STATUS_FAILED,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun incrementRetry(clientId: String, error: String) {
        pendingTransactionDao.incrementRetryCount(
            clientId = clientId,
            error = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun PendingTransactionEntity.toItemRequest(): TransactionItemRequest {
        return TransactionItemRequest(
            clientId = clientId,
            clientType = "app_android",
            source = source,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = accountId,
            description = description,
            date = date,
            time = time,
            tags = tags?.split(",")?.filter { it.isNotBlank() },
            clientCreatedAt = clientCreatedAt
        )
    }

    private enum class SyncResult {
        SUCCESS, UNAUTHORIZED, BUSINESS_ERROR, NETWORK_ERROR
    }

    companion object {
        const val WORK_NAME = "transaction_sync"
        private const val MAX_RETRY_COUNT = 5
        private const val HTTP_UNAUTHORIZED = 401
        private const val STATUS_SYNCED = "synced"
        private const val STATUS_FAILED = "failed"
    }
}
