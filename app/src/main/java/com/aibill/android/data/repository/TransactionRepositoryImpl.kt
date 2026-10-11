package com.aibill.android.data.repository

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
import com.aibill.android.domain.repository.TransactionQuery
import com.aibill.android.domain.repository.TransactionRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端那侧的账。
 *
 * 记一笔只在这里发生：送 `POST /transactions` → 按 `client_id` 核对 `created[]` 回执。
 * 核不平就是 [Result.Error]，本地不留任何痕迹（架构 §2.3 的成功判据）。
 */
@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val transactionApi: TransactionApi,
) : TransactionRepository {

    override suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>> {
        val request = CreateTransactionRequest(
            items = items.map { it.toItemRequest() }
        )
        return when (val response = safeApiCall { transactionApi.createTransactions(request) }) {
            is Result.Error -> response
            is Result.Loading -> response
            is Result.Success -> {
                val created = response.data.created
                // 成功判据 = 服务端返回 created 且按 client_id 匹配到 id。
                // code==0 但回执里少一条（漏字段 / 转换异常 / 只在 duplicates 里）都算没记上——
                // 放过去就会出现「本地以为记了、服务端没有」的中间态。
                val unconfirmed = items.filter { item ->
                    // TransactionDto.id 是非空 Int：匹配到即等于拿回了 id
                    created.none { it.clientId == item.clientId }
                }
                if (unconfirmed.isEmpty()) {
                    Result.Success(created.map { it.toDomain() })
                } else {
                    Timber.w("createTransactions 未获服务端确认: ${unconfirmed.map { it.clientId }}")
                    Result.Error(Result.ERROR_UNKNOWN, "服务端未确认这笔记录")
                }
            }
        }
    }

    override suspend fun getTransactions(query: TransactionQuery): Result<TransactionPage> {
        return safeApiCall {
            transactionApi.getTransactions(
                query.page, query.pageSize,
                query.startDate, query.endDate,
                query.type, query.categoryId, query.accountId,
                query.keyword, query.tag,
            )
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

    /** PR #61：回收站 */
    override suspend fun getTrash(): Result<List<Transaction>> {
        return safeApiCall { transactionApi.getTrash() }.map { paginated ->
            paginated.items.map { it.toDomain() }
        }
    }

    override suspend fun restoreTransaction(id: Int): Result<Unit> {
        return when (val response = safeApiCall { transactionApi.restoreTransaction(id) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun permanentDeleteTransaction(id: Int): Result<Unit> {
        return when (val response = safeApiCall { transactionApi.permanentDeleteTransaction(id) }) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> response
            is Result.Loading -> response
        }
    }

    override suspend fun getTags(): Result<List<String>> {
        return safeApiCall { transactionApi.getTags() }.map { it.items }
    }

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
        // 不再写死 MANUAL：通知自动记账的记录从服务端拉回来时也必须保留 source，
        // 否则 UI 的「⚡自动」标记 / 首页「自动记账 N 笔」永远不会显示。
        // 后端未返回 source（旧版本）时 fallback 到 MANUAL，行为与之前一致。
        source = TransactionSource.fromValue(source) ?: TransactionSource.MANUAL,
        createdAt = createdAt,
    )
}
