package com.aibill.android.data.repository

import android.content.Context
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.repository.TransactionPage
import com.aibill.android.domain.repository.TransactionQuery
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.service.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val transactionApi: TransactionApi,
    private val pendingTransactionDao: PendingTransactionDao,
    private val recordIntake: RecordIntake,
    @ApplicationContext private val context: Context,
) : TransactionRepository {

    override suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>> {
        val request = CreateTransactionRequest(
            items = items.map { it.toItemRequest() }
        )
        return safeApiCall { transactionApi.createTransactions(request) }.map { response ->
            response.created.map { it.toDomain() }
        }
    }

    /**
     * 落离线一笔，并在**写库之后**立刻排一次同步。
     *
     * ## 它已不再是「纯写库」
     *
     * 历史上这里只 `insert`，排同步全靠兜底：`NetworkMonitor` 的「不可用→可用」
     * 跳变、App 启动那一次、以及用户手动点同步。WiFi 常连时的瞬时失败记账会一直躺着，
     * 直到下一次跳变——而 `README.md` 与 Home 待同步横幅都在承诺「联网后会自动补传」。
     * 既然写库的那一刻就知道「这笔需要上传」，就该在这一刻排。
     *
     * 本函数现在只做一件事：把 `Transaction` 翻译成
     * [RecordIntent.ManualOffline] 交给入账 module。实体装配、跨表写入、排调度与
     * 排同步失败的处置（只记日志、不回滚、不抛出，理由见 `RecordIntakeImpl` 的类 KDoc）
     * 都已下沉到那里；下面四条契约**行为保持不变**，只是换了实现位置。
     *
     * ## 语义边界：Success 只代表「已入队」
     *
     * [SyncScheduler.scheduleSyncIfNeeded] 只是把 [SyncWorker] 入队
     * （`NetworkType.CONNECTED` 约束 + unique work 串行），**不代表已上传**。
     * 真实结果由队列状态与横幅呈现；调用方不得据此改写成「已同步/已上传」。
     *
     * ## 失败策略：只记日志，绝不回滚、绝不抛出
     *
     * 入队失败（WorkManager 抛异常）时这笔账**已经落库**，回滚等于丢用户数据；
     * 抛给调用方则会让异常逃出 `viewModelScope`——本工程**没有**全局
     * `CoroutineExceptionHandler`，未捕获异常走线程默认处理器 = **直接崩溃进程**；
     * 即便假设它被吞，`ManualRecordViewModel` 末尾的 `isSaving` 复位也会被异常跳过，
     * 按钮永久卡在「保存中」。剩余兜底仍有：
     * 网络跳变、启动排同步、Home 横幅的手动同步按钮。
     *
     * ## 副作用：批量离线记账会排出多个 worker
     *
     * [SyncScheduler.scheduleSyncIfNeeded] 用 `APPEND_OR_REPLACE`，连录 N 笔
     * 离线账就入队 N 个 worker；第 1 个跑完 `getAllPending()` 清空队列后，
     * 第 2…N 个各自再取一次空队列并 `Result.success()` 早退。**不造成重复上传**
     * （队列已空，且服务端按 `clientId` 的 `duplicates[]` 幂等），只是多几次
     * 取空队列与抢锁的开销。批量导入场景若嫌浪费，可在此按需节流。
     *
     * 当前唯一调用者：`ManualRecordViewModel`（仅网络错误分支）。
     */
    override suspend fun createTransactionOffline(transaction: Transaction) {
        // 顺序不变量「先写库、后排调度」由入账 module 保证。
        recordIntake.intake(RecordIntent.ManualOffline(transaction))
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

    override fun observeSyncStatus(): Flow<SyncQueueStatus> =
        pendingTransactionDao.observeSyncStatus()

    override suspend fun syncPending(): Result<Unit> {
        // PR 修复：原实现是空 stub，直接返回 Success 但啥也不做，导致
        // HomeViewModel.triggerSync() 误以为同步完成。真实同步在 [SyncWorker] 中执行，
        // 此处通过 [SyncScheduler] 入队 WorkManager，状态由 WorkInfo 流转。
        return try {
            SyncScheduler.scheduleSyncIfNeeded(context)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(-2, "触发同步失败: ${e.message}")
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
