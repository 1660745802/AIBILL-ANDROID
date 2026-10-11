package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction

/**
 * **服务端那侧**的账。
 *
 * 「记一笔」的唯一成功判据是服务端 `POST /transactions` 的回执（`created[]` 或
 * `duplicates[]`）里按 `client_id` 匹配到 id —— [createTransactions] 因此在 `code==0`
 * 之外还要逐条核对回执，少一条就当没记上。本地缓存不在这里写：入账 module 只在服务端
 * 确认之后才落库（`data/record/RecordIntakeImpl`），手工记账这条路则只打服务端。
 */
interface TransactionRepository {

    /**
     * 送服务端建一笔 / 一批。
     *
     * @return **只有**服务端把全部入参都回了执（`created[]` 或 `duplicates[]` 里按
     *   `client_id` 找得到、带 id）才是 [Result.Success]；否则 [Result.Error]，
     *   且此时本地**一个字节都没写**——调用方据此报错给用户、保留表单输入，
     *   不存在「先记上、回头再传」的中间态。
     */
    suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>>

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
