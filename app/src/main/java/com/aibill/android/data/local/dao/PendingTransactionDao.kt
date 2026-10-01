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

    @Query("UPDATE pending_transactions SET sync_status = :status, updated_at = :updatedAt WHERE client_id = :clientId")
    suspend fun updateSyncStatus(clientId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pending_transactions SET sync_status = :status, server_transaction_id = :serverId, updated_at = :updatedAt WHERE client_id = :clientId")
    suspend fun markSynced(clientId: String, serverId: Int, status: String = "synced", updatedAt: Long = System.currentTimeMillis())

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
