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

    /**
     * 删除全部通知记录（**切换服务器**清空本地缓存时调用）。
     *
     * 签名与 `PendingTransactionRepository.deleteAll()` / `CategoryRepository.deleteAll()` /
     * `AccountRepository.deleteAll()` 对齐——四者共同构成「清空本地缓存」的完整集合，
     * 必须与 `AuthRepositoryImpl.clearLocalCache()` **逐表一致（都是 4 张）**：
     * 改这里必须同步改那边，反之亦然。
     * 两边各有测试钉住 4 表：`ServerConfigViewModelTest`（换服务器）与
     * `AuthRepositoryImplTest`（换号登录）。
     *
     * 已知单点：新增第 5 张要清的表时，两处都要动——尚未抽公共 cleaner。
     */
    suspend fun deleteAll()
}
