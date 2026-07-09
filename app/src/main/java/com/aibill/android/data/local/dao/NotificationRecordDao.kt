package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aibill.android.data.local.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationRecordDao {

    @Insert
    suspend fun insert(entity: NotificationRecordEntity): Long

    @Query("SELECT * FROM notification_records WHERE status IN ('raw', 'parsed') ORDER BY received_at DESC")
    fun observePending(): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notification_records WHERE status = 'confirmed' ORDER BY received_at DESC LIMIT 20")
    fun observeConfirmed(): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notification_records WHERE (status IN ('raw', 'parsed')) OR (status = 'confirmed' AND received_at > :confirmedSince) ORDER BY received_at DESC LIMIT 50")
    fun observeAllWithConfirmedSince(confirmedSince: Long): Flow<List<NotificationRecordEntity>>

    @Query("SELECT COUNT(*) FROM notification_records WHERE status IN ('raw', 'parsed')")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE notification_records SET status = :status, linked_client_id = :clientId WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, clientId: String? = null)

    @Query("UPDATE notification_records SET parsed_amount = :amount, parsed_type = :type, parsed_description = :description, status = :status WHERE id = :id")
    suspend fun updateParsedResult(id: Long, amount: Int, type: String, description: String?, status: String = "parsed")

    @Query("SELECT * FROM notification_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NotificationRecordEntity?

    @Query("SELECT * FROM notification_records WHERE package_name = :packageName AND content = :content AND received_at > :since LIMIT 1")
    suspend fun findDuplicate(packageName: String, content: String, since: Long): NotificationRecordEntity?

    /** 入库前去重：同金额+60s内已有confirmed记录（防多渠道重复入库） */
    @Query("SELECT * FROM notification_records WHERE parsed_amount = :amount AND status = 'confirmed' AND received_at > :since LIMIT 1")
    suspend fun findRecentConfirmed(amount: Int, since: Long): NotificationRecordEntity?

    /**
     * 跨包名去重：查 60s 内不同包名已处理（confirmed 或 parsed）的同金额记录。
     */
    @Query("SELECT * FROM notification_records WHERE parsed_amount = :amount AND status IN ('confirmed', 'parsed') AND received_at > :since AND package_name != :excludePackageName LIMIT 1")
    suspend fun findRecentConfirmedFromOtherChannel(amount: Int, since: Long, excludePackageName: String): NotificationRecordEntity?

    @Query("DELETE FROM notification_records WHERE status = 'ignored' AND received_at < :before")
    suspend fun cleanIgnoredBefore(before: Long)

    @Query("SELECT * FROM notification_records WHERE package_name = :packageName AND received_at >= :since ORDER BY received_at DESC LIMIT 1")
    suspend fun findRecentByPackage(packageName: String, since: Long): NotificationRecordEntity?

    @Query("DELETE FROM notification_records")
    suspend fun deleteAll()
}
