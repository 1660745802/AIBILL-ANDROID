package com.aibill.android.domain.notification

import com.aibill.android.domain.model.NotificationRecord
import kotlinx.coroutines.flow.Flow

/**
 * 待审池 module：通知中心与首页红点读取 `notification_records` 的**唯一**入口。
 *
 * ## 为什么存在（而不是删掉）
 *
 * 收口前这里是 `NotificationRecordRepository`：6 个方法与 `NotificationRecordDao`
 * 一对一转发，interface ≈ implementation——典型的 shallow module。但**不能删**：
 * 删了 DAO 就会漏进 ViewModel，破坏「ViewModel 只经 interface 访问数据」的分层。
 * 于是升级成按**意图**命名的读取口：
 *
 * - 原来 `observePending` / `observeConfirmed` / `observeAllWithConfirmedSince`
 *   三个近似查询收敛成一个 [observe]——通知中心展示的就是「待审 + 最近已确认」；
 * - 方法按意图命名，只保留 4 个。
 *
 * ## 边界
 *
 * - **确认（confirm）不在这里**：它已经归 `RecordIntake`（服务端确认 + 跨表写入），
 *   这里再开一个入口就会出现第二条入账路径。
 * - 写入只保留 [ignore]（纯状态标记，不入账、不发回执）。
 *
 * 返回类型是 domain 的 [NotificationRecord] 而非 Entity——「Entity 不出 data 层」。
 */
interface ReviewQueue {

    /**
     * 观察待审（`parsed` + 历史遗留的 `raw`）+ [sinceMillis] 之后已确认的记录。
     *
     * `raw` 是 ≤1.6.1 的解析失败原文（现已不再写入，见 [CaptureArchive]）：保留读取是为了
     * 老用户升级后不静默丢历史数据，通知中心仍可确认/忽略。
     *
     * @param sinceMillis 已确认记录的展示窗口下界（通知中心传 24h 前的时间戳）
     */
    fun observe(sinceMillis: Long): Flow<List<NotificationRecord>>

    /** 观察待审计数（首页红点 / 通知中心角标）。 */
    fun observePendingCount(): Flow<Int>

    /** 按 id 取一条；确认 / 忽略前核对状态用。 */
    suspend fun findById(id: Long): NotificationRecord?

    /** 忽略一条：只把状态置为 `ignored`，不入账、不发回执。 */
    suspend fun ignore(id: Long)
}
