package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.service.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val transactionApi: TransactionApi,
    private val pendingTransactionDao: PendingTransactionDao,
) : TransactionRepository {

    override suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>> {
        val request = CreateTransactionRequest(
            items = items.map { it.toItemRequest() }
        )
        return safeApiCall { transactionApi.createTransactions(request) }.map { response ->
            response.created.map { it.toDomain() }
        }
    }

    override suspend fun createTransactionOffline(transaction: Transaction) {
        val entity = PendingTransactionEntity(
            clientId = transaction.clientId,
            type = transaction.type.value,
            amount = transaction.amount,
            categoryId = transaction.categoryId,
            accountId = transaction.accountId,
            targetAccountId = transaction.targetAccountId,
            description = transaction.description,
            date = transaction.date,
            time = transaction.time,
            tags = transaction.tags?.joinToString(","),
            source = transaction.source.value,
            clientCreatedAt = java.time.Instant.now().toString(),
            syncStatus = "pending",
        )
        pendingTransactionDao.insert(entity)
    }

    override suspend fun getTransactions(
        page: Int, pageSize: Int, startDate: String?,
        endDate: String?, type: String?, categoryId: Int?, keyword: String?,
    ): Result<List<Transaction>> {
        return safeApiCall {
            transactionApi.getTransactions(page, pageSize, startDate, endDate, type, categoryId, null, keyword)
        }.map { paginated -> paginated.items.map { it.toDomain() } }
    }

    override suspend fun deleteTransaction(id: Int): Result<Unit> {
        return safeApiCall { transactionApi.deleteTransaction(id) }
    }

    override fun observePendingCount(): Flow<Int> =
        pendingTransactionDao.observePendingCount()

    override suspend fun syncPending(): Result<Unit> = Result.Success(Unit)

    private fun Transaction.toItemRequest() = TransactionItemRequest(
        clientId = clientId,
        source = source.value,
        type = type.value,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        targetAccountId = targetAccountId,
        description = description,
        date = date,
        time = time,
        tags = tags,
        clientCreatedAt = java.time.Instant.now().toString(),
    )

    private fun TransactionDto.toDomain() = Transaction(
        id = id,
        clientId = clientId.orEmpty(),
        type = TransactionType.fromValue(type),
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        accountId = accountId,
        accountName = accountName,
        targetAccountId = targetAccountId,
        targetAccountName = targetAccountName,
        description = description,
        date = date,
        time = time,
        tags = tagsList(),
        source = TransactionSource.MANUAL,
        createdAt = createdAt,
    )
}
