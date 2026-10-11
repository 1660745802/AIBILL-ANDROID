package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

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

    /**
     * 离线队列的原子快照（pending / failed 两个分区，见 [SyncQueueStatus]）。
     *
     * 唯一的读取口径，取代了原 `observePendingCount(): Flow<Int>`——
     * 后者只数 `pending`，把 failed 行的存在与否完全交给调用方猜，
     * 于是横幅在「全部行都同步失败」时直接消失，等价于告诉用户
     * 「没有未同步的数据」。两个数字必须来自同一条聚合查询，
     * 才不会在两次独立读取之间出现不一致的中间状态。
     */
    fun observeSyncStatus(): Flow<SyncQueueStatus>

    suspend fun syncPending(): Result<Unit>

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
