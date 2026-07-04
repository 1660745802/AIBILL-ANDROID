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

    @Query("SELECT * FROM pending_transactions ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentTransactions(limit: Int = 20): Flow<List<PendingTransactionEntity>>

    @Query("DELETE FROM pending_transactions WHERE sync_status = 'synced' AND updated_at < :before")
    suspend fun cleanSyncedBefore(before: Long)

    @Query("DELETE FROM pending_transactions")
    suspend fun deleteAll()
}
