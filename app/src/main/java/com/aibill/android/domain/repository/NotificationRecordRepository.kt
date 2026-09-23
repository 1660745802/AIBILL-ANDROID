package com.aibill.android.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * 通知记录仓储接口。**ViewModel 只能通过此接口访问通知记录数据**，
 * 不允许直接注入 DAO（保持分层）。
 */
interface NotificationRecordRepository {
    /** 观察待确认通知数量（pending pool） */
    fun observePendingCount(): Flow<Int>
}
