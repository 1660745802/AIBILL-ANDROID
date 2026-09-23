package com.aibill.android.domain.repository

import com.aibill.android.data.local.entity.PendingTransactionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 待同步交易仓储接口。**ViewModel 只能通过此接口访问离线队列数据**，
 * 不允许直接注入 DAO。
 *
 * "Pending" = 离线写入但尚未同步到服务端的交易（syncStatus = pending/failed）
 */
interface PendingTransactionRepository {
    /** 插入单条待同步交易 */
    suspend fun insert(entity: PendingTransactionEntity)

    /** 当前未同步条数 */
    suspend fun getPendingCount(): Int

    /** 删除所有（用于"切换服务器清空本地数据"场景） */
    suspend fun deleteAll()

    /** 根据 clientId 查询 */
    suspend fun findByClientId(clientId: String): PendingTransactionEntity?
}
