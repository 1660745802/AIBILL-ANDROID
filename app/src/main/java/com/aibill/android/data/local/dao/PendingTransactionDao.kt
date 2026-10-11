package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.SyncQueueStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PendingTransactionEntity>)

    @Query("SELECT * FROM pending_transactions WHERE sync_status = 'pending' ORDER BY created_at ASC")
    suspend fun getAllPending(): List<PendingTransactionEntity>

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'pending'")
    suspend fun getPendingCount(): Int

    /**
     * 统计任意未同步成功的记录（pending + failed）。
     * 用于 SyncWorker 在发现还有未处理项时继续 retry，
     * 但又不会重复遍历已 synced 的项。
     */
    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status IN ('pending', 'failed')")
    suspend fun getAnyUnsyncedCount(): Int

    /**
     * 排队等上传的条数（`sync_status = 'pending'`）。
     *
     * ⚠️ **与 `SyncQueueStatus.pending` 同口径**：后者是本查询的聚合形态
     * （见 [observeSyncStatus]），二者都只数 `pending`、都不计 `failed`。
     * 改这里的 WHERE 必须同时改 [observeSyncStatus]，反之亦然——
     * 两个数字一旦分岔，横幅的标题与显隐就会互相矛盾。
     *
     * 这是**刻意**只数 pending：本方法回答「排队等上传的条数」，
     * 而 `failed` 需要用户介入，混进去会让数字与「实际可自动续传的条数」对不上。
     * 需要 pending + failed 时用 [getAnyUnsyncedCount]。
     */
    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'pending'")
    fun observePendingCount(): Flow<Int>

    /**
     * 离线队列的**原子快照**：一条查询同时产出 `pending` 与 `failed` 两个分区计数，
     * 一次读取、同一份快照，供 `TransactionRepository.observeSyncStatus()` 直转。
     *
     * ## 为什么用 `COUNT(CASE WHEN ...)` 而不是 `SUM(CASE WHEN ... THEN 1 END)`
     *
     * `SUM()` 在空表（或全部不匹配）时按 SQL 标准返回 **NULL**。而 Room 对非空 `Int`
     * 生成的是**裸 `_cursor.getInt(i)`**（`PendingTransactionDao_Impl.observeSyncStatus()`，
     * 无 `isNull` 分支），能不炸只是因为 Android `Cursor` 对 NULL 返回 **0**
     * 这一**未文档化的实现行为**——也就是说 SUM 的正确性压在这条**实现细节**上，
     * 而不是写在 SQL 语义里。
     * （实测：把两列换成 SUM 不加 `COALESCE`，四个用例照样全绿——因为上面这条
     * 实现细节兜住了。所以这条禁令靠注释，不靠测试。）
     *
     * `COUNT(expr)` 在 SQL 层就永远返回非 NULL 整数、零行也返回 0，
     * 不依赖任何底层约定，空表稳定 `0 / 0`，也就不需要 `COALESCE`。
     *
     * ## ⚠️ 禁止日后给本查询加 `GROUP BY`
     *
     * 无 `GROUP BY` 的聚合**必定返回恰好一行**，`moveToFirst()` 恒为 true。
     * 而 Room 生成的读取逻辑是
     * `if (_cursor.moveToFirst()) { … } else { _result = null; }`
     * —— **0 行时它返回 null**，但 Kotlin 侧类型是非空 `SyncQueueStatus`，
     * null 只会在很晚的非空边界炸出来，而不是在查询处就报错。
     * 加上 `GROUP BY sync_status` 后，空表（或某个状态无行）就是 0 行；
     * 同时 `pending` / `failed` 会变成「缺 key 就不出现」，
     * 还得在 Kotlin 侧补默认值，又多出一个「漏 key 即 0」的静默错法。要加维度就另开一个查询。
     *
     * 列别名与 `SyncQueueStatus` 的构造参数同名（`pending` / `failed`），
     * Room 构造器映射靠名字对齐，写错了会在编译期报找不到 setter/field，
     * 不会静默变成 0。
     */
    @Query(
        """
        SELECT COUNT(CASE WHEN sync_status = 'pending' THEN 1 END) AS pending,
               COUNT(CASE WHEN sync_status = 'failed' THEN 1 END) AS failed
        FROM pending_transactions
        """
    )
    fun observeSyncStatus(): Flow<SyncQueueStatus>

    /**
     * 只用于**成功**路径（无 serverId 时降级标 synced）。
     *
     * ⚠️ **禁止用于失败路径**：本方法不写 `last_error`，用它标 failed 会让
     * [resetFailedMatching] 的 WHERE 条件（`last_error LIKE :errorPattern`）永远匹配不到，
     * 401 这类**可恢复**失败就会变成真终态、离线记账永久卡死在本地。
     * 失败一律走 [markFailed]。
     */
    @Query("UPDATE pending_transactions SET sync_status = :status, updated_at = :updatedAt WHERE client_id = :clientId")
    suspend fun updateSyncStatus(clientId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 标记为**终态失败**，并在**同一条 UPDATE** 中原子写入 `sync_status` + 失败原因 + 时间戳。
     *
     * 为什么必须一条 SQL：分成「先 updateSyncStatus 再写 last_error」两步会在中间留下
     * 一个 `sync_status='failed'` 但 `last_error IS NULL` 的窗口——此时
     * [resetFailedMatching] 匹配不上，这条可恢复失败就被永久判死。
     *
     * @param error 失败原因，须与 [SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN] 等恢复判据可匹配。
     */
    @Query(
        """
        UPDATE pending_transactions
        SET sync_status = 'failed', last_error = :error, updated_at = :updatedAt
        WHERE client_id = :clientId
        """
    )
    suspend fun markFailed(clientId: String, error: String, updatedAt: Long = System.currentTimeMillis())

    /**
     * 标记同步成功并写入服务端 transaction id。
     *
     * @return 受影响行数：**0 = 这条 pending 在同步期间已被删除**（用户撤销）。
     * [com.aibill.android.service.SyncWorker] 用它判断「服务端已建好、但本地没人要了」，
     * 从而触发补偿删除。SQL 不变，Room 对 UPDATE 语句自动返回受影响行数。
     */
    @Query("UPDATE pending_transactions SET sync_status = :status, server_transaction_id = :serverId, updated_at = :updatedAt WHERE client_id = :clientId")
    suspend fun markSynced(clientId: String, serverId: Int, status: String = "synced", updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE pending_transactions SET retry_count = retry_count + 1, last_error = :error, updated_at = :updatedAt WHERE client_id = :clientId")
    suspend fun incrementRetryCount(clientId: String, error: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM pending_transactions WHERE sync_status = 'failed' ORDER BY updated_at DESC")
    fun observeFailedTransactions(): Flow<List<PendingTransactionEntity>>

    /**
     * 把「因 401 卡住」的 failed 记录重置回 pending，使其可被 [getAllPending] 再次捞起。
     *
     * 背景：SyncWorker 遇到 401 会标 failed 并返回 Result.failure() 等用户重新登录。
     * 但 failed 状态是**终态**——getAllPending() 只捞 'pending'，所以「Token 过期」
     * 这种**可恢复**失败在重新登录后原本永远不会被重试，离线记账会静默卡死在本地。
     *
     * 只按 [errorPattern] 精确匹配 last_error（而非重置全部 failed），
     * 避免把「业务错误 / 服务端异常 / 超过最大重试」这些不可恢复的失败也重置成无限重试。
     *
     * ⚠️ 本查询**完全依赖** [markFailed] 确实写入了 `last_error`。历史上
     * SyncWorker.markFailed 误用 [updateSyncStatus]（不写 last_error），
     * 导致本方法恒返回 0 —— 401 恢复是死代码。现已由
     * `SyncWorkerSyncTransactionTest`（reason 断言）+ `PendingTransactionDaoMarkFailedTest`
     * （写入→重置的端到端不变量）双重锁住。
     */
    @Query(
        """
        UPDATE pending_transactions
        SET sync_status = 'pending', retry_count = 0, last_error = NULL, updated_at = :now
        WHERE sync_status = 'failed' AND last_error LIKE :errorPattern
        """
    )
    suspend fun resetFailedMatching(errorPattern: String, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM pending_transactions ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentTransactions(limit: Int = 20): Flow<List<PendingTransactionEntity>>

    @Query("DELETE FROM pending_transactions WHERE sync_status = 'synced' AND updated_at < :before")
    suspend fun cleanSyncedBefore(before: Long)

    @Query("DELETE FROM pending_transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM pending_transactions WHERE client_id = :clientId LIMIT 1")
    suspend fun findByClientId(clientId: String): PendingTransactionEntity?

    @Query("DELETE FROM pending_transactions WHERE client_id = :clientId")
    suspend fun deleteByClientId(clientId: String)
}
