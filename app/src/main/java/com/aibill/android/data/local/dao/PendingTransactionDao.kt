package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aibill.android.data.local.entity.PendingTransactionEntity
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

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'pending'")
    fun observePendingCount(): Flow<Int>

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
