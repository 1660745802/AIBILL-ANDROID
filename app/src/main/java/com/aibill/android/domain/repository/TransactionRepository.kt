package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.record.RecordIntake

/**
 * **服务端那侧**的账。
 *
 * ## 离线队列不在这里
 *
 * 队列的读取与排调度归 [com.aibill.android.domain.queue.SyncQueue]（离线队列 module）。
 * 收口前这里有 `observeSyncStatus()` 与 `syncPending()`：前者只是 DAO 的一行转发，
 * 后者只是「排调度 + 包一层 `Result`」，两者都把「已入队 ≠ 已上传」这句告诫
 * 复制在各自的 KDoc 里，越传越没人看。
 *
 * 手工记账落离线（[createTransactionOffline]）仍走本接口：它表达的是
 * 「这笔账要成为本地事实」的用户意图，装配细节交给 [RecordIntake]。
 */
interface TransactionRepository {

    suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>>

    suspend fun createTransactionOffline(transaction: Transaction)

    /**
     * PR #47：返回包装类 TransactionPage（含 items + total），
     * 调用方按 PRD §6.5.2 (page * page_size) < total 准确判定 hasMore。
     */
    suspend fun getTransactions(query: TransactionQuery): Result<TransactionPage>

    suspend fun deleteTransaction(id: Int): Result<Unit>

    /** PR #61：详情页按 id 单条拉取，绕过 TransactionApi.getTransaction */
    suspend fun getTransaction(id: Int): Result<Transaction>

    /** PR #61：详情页保存修改，绕过 TransactionApi.updateTransaction */
    suspend fun updateTransaction(id: Int, body: Map<String, Any>): Result<Transaction>

    /** PR #61：回收站 (TrashViewModel 之前直接调 TransactionApi) */
    suspend fun getTrash(): Result<List<Transaction>>
    suspend fun restoreTransaction(id: Int): Result<Unit>
    suspend fun permanentDeleteTransaction(id: Int): Result<Unit>

    suspend fun getTags(): Result<List<String>>
}

/**
 * 分页结果包装类，保留 total 用于 hasMore 判定。
 */
/**
 * 流水查询条件。集中 9 个参数为 data class，避免 LongParameterList。
 */
data class TransactionQuery(
    val page: Int = 1,
    val pageSize: Int = 20,
    val startDate: String? = null,
    val endDate: String? = null,
    val type: String? = null,
    val categoryId: Int? = null,
    val accountId: Int? = null,
    val keyword: String? = null,
    val tag: String? = null,
)

data class TransactionPage(
    val items: List<Transaction>,
    val total: Int,
)
