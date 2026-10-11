package com.aibill.android.domain.queue

import com.aibill.android.domain.model.SyncQueueStatus
import kotlinx.coroutines.flow.Flow

/**
 * 离线队列 module：`pending_transactions` 这张表的**唯一**对外入口。
 *
 * 注意「清空本地缓存」不在这里兑现（架构候选 4）：换号 / 换服务器要清的
 * 是 4 张表，那份清单归 `LocalDataOwnership`（本 interface 只负责队列表本身）。
 *
 * ## 收口了什么
 *
 * 「排调度 / 快照 / 失败恢复」原本散在四处，各自带一份「已入队 ≠ 已上传」的告诫：
 * `TransactionRepository.syncPending()`（一行转发）、`PendingTransactionRepository`
 * （4 个方法一对一转发，其中 `insert` / `findByClientId` 零调用）、
 * `SyncScheduler`（static object + `Context` 参数，6 个调用点）、以及
 * `AuthRepositoryImpl` 里直连 DAO 的 401 恢复。现在四件事收在一个接口后面。
 *
 * ## 三条硬规则（**不要在本 module 里改**）
 *
 * 1. **快照只有一个真相**：[observeStatus] 是 pending / failed 分区的唯一读取口径，
 *    底层是一条 `COUNT(CASE WHEN …)` 聚合查询。两个数字来自同一次读取，
 *    从不相加（见 [SyncQueueStatus] 的 KDoc）。
 * 2. **排调度不等于已上传**：[requestSync] 返回的 [SyncRequest.Enqueued]
 *    只代表 WorkManager 接受了入队请求。把 `Enqueued` 改写成「已同步 / 已上传」文案
 *    = 新的谎报，真实结果只在 [observeStatus] 的快照与横幅里。
 * 3. **401 恢复只认 `last_error` 模式**：写入侧必须走
 *    `PendingTransactionDao.markFailed`（同一条 UPDATE 写 `sync_status` + `last_error`），
 *    否则 [recoverTokenExpiredFailures] 恒返回 0，离线记账永久卡死在本地。
 *
 * ## 入队为什么不在本接口上
 *
 * 入队要写 `pending_transactions`，并按形态联动 `notification_records`
 * （建审计行 / 回填 `status` + `linked_client_id`），签名里必然出现 Room entity——
 * 那正是收口前 `PendingTransactionRepository.insert(entity: PendingTransactionEntity)`
 * 的老毛病（domain 接口直接收发 entity，于是它只能是一层转发）。
 * 因此入队这一口是本 module 的 data 层 `RecordWriter`，只由入账 module
 * （[com.aibill.android.domain.record.RecordIntake]）使用，**对外接口不带 entity**。
 *
 * ## 快照的口径对照（两个数字回答不同问题，不要互相复用）
 *
 * | 方法 | 回答 | 计数 |
 * |---|---|---|
 * | [observeStatus].pending | 排队等上传的条数（横幅） | `sync_status='pending'` |
 * | [observeStatus].failed | 有多少需要人管（横幅） | `sync_status='failed'` |
 * | [unsyncedCount] | 这次 [clear] 会带走多少用户数据（换服务器前的警告） | `pending + failed` |
 *
 * 前两个从不相加；第三个是**唯一**允许相加的地方，且只用于清空前的警告。
 */
interface SyncQueue {

    /**
     * 队列快照：pending / failed 两个分区，一次读取、同一份快照。
     *
     * 唯一的分区读取口径（取代收口前散在 `TransactionRepository` /
     * `PendingTransactionRepository` / DAO 三处的读法）。
     */
    fun observeStatus(): Flow<SyncQueueStatus>

    /**
     * 未确认上传的行数（= `pending` + `failed`），**只用于 [clear] 前的警告**。
     *
     * 为什么必须含 `failed`：`failed` 虽是本地终态，却同样会被 [clear] 一起清空。
     * 漏算它 = `ServerConfigViewModel` 在「全部行都 failed」时得到 0 →
     * 不弹二次确认 → 用户未被警告即被删数据。
     *
     * ⚠️ 不要拿它当「排队等上传的条数」——那是 [observeStatus].pending 的语义。
     */
    suspend fun unsyncedCount(): Int

    /**
     * 清空整张队列表（换服务器 / 换号）。
     *
     * 触发方必须先看 [unsyncedCount] 警告用户；清空范围与
     * 「清空本地缓存」其余三张表（categories / accounts / notification_records）
     * 的组合只存在于本地数据归属 module（`LocalDataOwnership`，
     * 架构候选 4 起），由 `LocalDataOwnershipImplTest` 用**真实 Room**
     * 按表名逐张钉住。
     *
     * ⚠️ 不要再从调用方直接清队列表：那份清单必须是全仓唯一的一份。
     */
    suspend fun clear()

    /**
     * 排一次同步（OneTime + `NetworkType.CONNECTED` + `APPEND_OR_REPLACE`）。
     *
     * **不抛异常**：WorkManager 不可用时返回 [SyncRequest.Rejected]，
     * 由调用方按自己的语义处置（入账侧只记日志、首页侧报错给用户）。
     *
     * 幂等性来自 WorkManager 的 `APPEND_OR_REPLACE`（上次 worker 任意状态都允许新 worker
     * 排上队，避免 KEEP 导致「上次成功 → 新 pending 永远等不到下次同步」）：
     * 连排 N 次就是 N 个 worker 依次跑，第 2…N 个会发现队列已空并早退，
     * **不造成重复上传**（服务端按 `clientId` 的 `duplicates[]` 幂等）。
     */
    fun requestSync(): SyncRequest

    /**
     * 把「401 卡住」的失败行重置回 pending，使其可被自动续传。
     *
     * `failed` 是终态（`getAllPending()` 只捞 `pending`），所以 401 这类**可恢复**
     * 失败必须在用户重新登录时显式复位，否则离线记账静默卡死在本地。
     *
     * 只按 `last_error LIKE '…Token expired…'` 精确匹配，不重置全部 failed——
     * 业务错误 / 服务端异常 / 超最大重试是**不可恢复**的，放进去就是无限重试死循环。
     *
     * @return 被复活的行数（0 = 没有 401 卡住的行）
     */
    suspend fun recoverTokenExpiredFailures(): Int
}

/**
 * [SyncQueue.requestSync] 的结果。
 *
 * ## 为什么要有这个类型
 *
 * 收口前 `TransactionRepository.syncPending()` 返回 `Result<Unit>`，于是
 * `Result.Success` 只能靠 4 处 KDoc 重复解释「Success 只代表已入队」。
 * 现在「已入队」有了自己的名字：[Enqueued]。想写「已同步 / 已上传」的人
 * 得先解释清楚为什么要把 [Enqueued] 当成同步结果——那一刻谎报就现形了。
 */
sealed interface SyncRequest {

    /** WorkManager 接受了入队请求。**不代表已上传**，真实结果见 [SyncQueue.observeStatus]。 */
    object Enqueued : SyncRequest

    /** 连入队都没成功（WorkManager 不可用）。[cause] 是原始异常，调用方据此报错。 */
    data class Rejected(val cause: Throwable) : SyncRequest
}
