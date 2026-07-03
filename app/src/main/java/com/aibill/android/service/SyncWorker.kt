package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aibill.android.data.local.dao.PendingTransactionDao
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
    private val transactionApi: TransactionApi
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val pendingList = pendingTransactionDao.getAllPending()
        if (pendingList.isEmpty()) return Result.success()

        for (entity in pendingList) {
            if (entity.retryCount >= MAX_RETRY_COUNT) {
                markFailed(entity.clientId, "Max retry count exceeded")
                continue
            }

            val result = syncTransaction(entity)
            when (result) {
                SyncResult.SUCCESS -> markSynced(entity.clientId)
                SyncResult.UNAUTHORIZED -> return Result.failure()
                SyncResult.BUSINESS_ERROR -> markFailed(entity.clientId, "Business error")
                SyncResult.NETWORK_ERROR -> incrementRetry(entity.clientId, "Network error")
            }
        }

        val remainingCount = pendingTransactionDao.getPendingCount()
        return if (remainingCount > 0) Result.retry() else Result.success()
    }

    private suspend fun syncTransaction(entity: PendingTransactionEntity): SyncResult {
        return try {
            val request = CreateTransactionRequest(
                items = listOf(entity.toItemRequest())
            )
            transactionApi.createTransactions(request)
            SyncResult.SUCCESS
        } catch (e: HttpException) {
            when (e.code()) {
                HTTP_UNAUTHORIZED -> SyncResult.UNAUTHORIZED
                else -> SyncResult.BUSINESS_ERROR
            }
        } catch (_: IOException) {
            SyncResult.NETWORK_ERROR
        }
    }

    private suspend fun markSynced(clientId: String) {
        pendingTransactionDao.updateSyncStatus(
            clientId = clientId,
            status = STATUS_SYNCED,
            updatedAt = System.currentTimeMillis()
        )
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
