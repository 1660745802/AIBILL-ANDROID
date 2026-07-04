package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {

    suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>>

    suspend fun createTransactionOffline(transaction: Transaction)

    /**
     * PR #47：返回包装类 TransactionPage（含 items + total），
     * 调用方按 PRD §6.5.2 (page * page_size) < total 准确判定 hasMore。
     */
    suspend fun getTransactions(
        page: Int = 1,
        pageSize: Int = 20,
        startDate: String? = null,
        endDate: String? = null,
        type: String? = null,
        categoryId: Int? = null,
        accountId: Int? = null,
        keyword: String? = null,
    ): Result<TransactionPage>

    suspend fun deleteTransaction(id: Int): Result<Unit>

    /** PR #61：详情页按 id 单条拉取，绕过 TransactionApi.getTransaction */
    suspend fun getTransaction(id: Int): Result<Transaction>

    /** PR #61：详情页保存修改，绕过 TransactionApi.updateTransaction */
    suspend fun updateTransaction(id: Int, body: Map<String, Any>): Result<Transaction>

    fun observePendingCount(): Flow<Int>

    suspend fun syncPending(): Result<Unit>
}

/**
 * 分页结果包装类，保留 total 用于 hasMore 判定。
 */
data class TransactionPage(
    val items: List<Transaction>,
    val total: Int,
)
