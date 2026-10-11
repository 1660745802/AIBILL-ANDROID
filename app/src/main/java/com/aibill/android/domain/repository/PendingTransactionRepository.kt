package com.aibill.android.domain.repository

import com.aibill.android.data.local.entity.PendingTransactionEntity

/**
 * 待同步交易仓储接口。**ViewModel 只能通过此接口访问离线队列数据**，
 * 不允许直接注入 DAO。
 *
 * ## 本接口的计数方法只有一个：[getAnyUnsyncedCount]
 *
 * 它返回 `sync_status IN ('pending', 'failed')`，即「尚未确认上传到服务端」的全部行。
 *
 * **不要在本接口再新增一个 pending-only 的计数方法。** 注意这句话的范围仅限**本接口**，
 * 理由不是「pending-only 是错的」，而是本接口只有一个计数调用方
 * `ServerConfigViewModel.hasPendingData()`，其语义是「切换服务器时会被 [deleteAll]
 * 一并带走的数据有多少」：`failed` 虽是本地终态，却同样被 [deleteAll] 清空，
 * 漏算它 = 用户在毫无预警的情况下被删数据。一个调用方、一种语义，
 * 就不需要第二种计数方法——加了必然有人用错。
 *
 * ## ⚠️ 仓库里确实另有一种 pending-only 口径，那是**刻意**的，不是 bug
 *
 * `TransactionRepository.observePendingCount()`（→ `HomeViewModel` 的待同步横幅 /
 * `pendingSyncCount`）走的是 `PendingTransactionDao.observePendingCount()`，
 * SQL 为 `WHERE sync_status = 'pending'`，**故意不计 failed**：横幅要显示的是
 * 「排队等上传的条数」，而 `failed` 需要用户介入，混进去会让横幅数字与
 * 「实际可自动续传的条数」对不上。
 *
 * 所以**不要**因为本接口写了「含 failed」就去把横幅口径也改成 pending+failed，
 * 也不要反过来把本接口改成 pending-only。两者回答不同问题，各自正确。
 */
interface PendingTransactionRepository {
    /** 插入单条待同步交易 */
    suspend fun insert(entity: PendingTransactionEntity)

    /**
     * 未同步条数（= `pending` + `failed`）。
     *
     * 与 [com.aibill.android.data.local.dao.PendingTransactionDao.getAnyUnsyncedCount] 同义。
     * **不是** `getPendingCount()`——那个只数 `pending`；`failed` 虽是终态，
     * 但它同样会在切换服务器时被 [deleteAll] 清空，必须一并计入警告范围。
     *
     * 用错口径的后果：`ServerConfigViewModel` 在「全部行都 failed」时得到 0
     * → `hasPendingData()` 为 false → 不弹二次确认 → 用户未被警告即被清空本地缓存。
     */
    suspend fun getAnyUnsyncedCount(): Int

    /** 删除所有（用于"切换服务器清空本地数据"场景） */
    suspend fun deleteAll()

    /** 根据 clientId 查询 */
    suspend fun findByClientId(clientId: String): PendingTransactionEntity?
}
