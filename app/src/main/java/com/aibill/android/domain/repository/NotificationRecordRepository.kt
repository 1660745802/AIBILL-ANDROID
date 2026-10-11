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
     * 删除全部通知记录。
     *
     * ⚠️ **当前生产零调用**（架构候选 4 后）：「清空本地缓存」统一经本地数据归属 module
     * （`LocalDataOwnership`），它直接持有 `NotificationRecordDao`，不再走 Repository。
     * 本表属于「本地用户数据」四张表之一（pending/categories/accounts/notification_records），
     * 那份清空清单是全仓唯一一份（`LocalDataOwnershipImpl.clearOwnedTables`），
     * 由 `LocalDataOwnershipImplTest` 用真实 Room 按表名钉住。
     * 收口前 `AuthRepositoryImpl.clearLocalCache()` 与 `ServerConfigViewModel.onSave`
     * 各手列一遍，彼此靠 KDoc 互保。
     *
     * 保留本方法是待拍板项（见汇报）：`CategoryRepository.deleteAll()` /
     * `AccountRepository.deleteAll()` 同样变成零调用——它们是「同一个清单的第二个入口」，
     * 留着就等于允许有人只清一半。
     */
    suspend fun deleteAll()
}
