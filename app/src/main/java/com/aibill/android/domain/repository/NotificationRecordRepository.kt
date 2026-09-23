package com.aibill.android.domain.repository

import com.aibill.android.data.local.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 通知记录仓储接口。**ViewModel 只能通过此接口访问通知记录数据**，
 * 不允许直接注入 DAO（保持分层）。
 */
interface NotificationRecordRepository {
    // ===== 观察 =====
    /** 观察待确认通知数量（pending pool） */
    fun observePendingCount(): Flow<Int>

    /** 观察待处理通知（status = raw/parsed） */
    fun observePending(): Flow<List<NotificationRecordEntity>>

    /** 观察已确认通知（status = confirmed） */
    fun observeConfirmed(): Flow<List<NotificationRecordEntity>>

    /** 观察 [sinceMillis] 之后的所有已确认通知 */
    fun observeAllWithConfirmedSince(sinceMillis: Long): Flow<List<NotificationRecordEntity>>

    // ===== 单条查询 =====
    suspend fun findById(id: Long): NotificationRecordEntity?

    // ===== 写入 =====
    suspend fun updateStatus(id: Long, status: String, clientId: String? = null)
}
