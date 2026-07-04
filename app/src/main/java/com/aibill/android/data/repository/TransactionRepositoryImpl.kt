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
import com.aibill.android.domain.repository.TransactionPage
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
            // PR #43：冗余存 name/icon，未同步期间列表展示不需 join 分类表
            categoryName = transaction.categoryName,
            categoryIcon = transaction.categoryIcon,
            accountId = transaction.accountId,
            accountName = transaction.accountName,
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
        endDate: String?, type: String?, categoryId: Int?, accountId: Int?, keyword: String?,
    ): Result<TransactionPage> {
        return safeApiCall {
            transactionApi.getTransactions(page, pageSize, startDate, endDate, type, categoryId, accountId, keyword)
        }.map { paginated ->
            TransactionPage(
                items = paginated.items.map { it.toDomain() },
                total = paginated.total,
            )
        }
    }

    override suspend fun deleteTransaction(id: Int): Result<Unit> {
        return try {
            val response = transactionApi.deleteTransaction(id)
            if (response.code == 0) Result.Success(Unit)
            else Result.Error(response.code, response.message)
        } catch (e: Exception) {
            Result.Error(-1, e.message ?: "删除失败")
        }
    }

    /**
     * PR #61：单条交易查询
     */
    override suspend fun getTransaction(id: Int): Result<Transaction> {
        return safeApiCall { transactionApi.getTransaction(id) }.map { dto -> dto.toDomain() }
    }

    /**
     * PR #61：编辑交易（详情页保存）
     */
    override suspend fun updateTransaction(id: Int, body: Map<String, Any>): Result<Transaction> {
        return safeApiCall { transactionApi.updateTransaction(id, body) }.map { dto -> dto.toDomain() }
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
        type = TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
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
