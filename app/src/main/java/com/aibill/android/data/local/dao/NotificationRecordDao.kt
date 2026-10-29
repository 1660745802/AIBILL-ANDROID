package com.aibill.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aibill.android.data.local.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationRecordDao {

    @Insert
    suspend fun insert(entity: NotificationRecordEntity): Long

    /**
     * 因 AI 鉴权失败（401）而未能解析的待补偿记录。
     *
     * 2026-10-04：token 过期时 AI 调用抛 401，旧实现直接 catch 后丢弃，
     * 真实交易（招行 20.00 / 交通卡 1.80）静默消失且用户无感知。
     * 现改为落库标记，等用户重新登录后由 [NotificationProcessor] 重放。
     *
     * 复用现有表（status 是自由字符串）而非新建表 → **无需 Room migration**。
     * 该状态不命中任何 observe* 查询，不会混进待审/通知中心列表。
     */
    @Query("SELECT * FROM notification_records WHERE status = 'auth_pending' AND received_at > :since ORDER BY received_at ASC")
    suspend fun findAuthPending(since: Long): List<NotificationRecordEntity>

    /**
     * 通知中心列表：待审（`raw` / `parsed`）+ [confirmedSince] 之后已确认 + [ignoredSince] 之后已忽略。
     *
     * `ignored` 进列表是为了「忽略可找回」（用户点错能恢复）；窗口与 `confirmed` 同宽，
     * 避免已忽略区无限增长。`LIMIT 100` 是因为三类共用一份列表，比原先只承载两类时放宽。
     */
    @Query(
        "SELECT * FROM notification_records WHERE status IN ('raw', 'parsed') " +
            "OR (status = 'confirmed' AND received_at > :confirmedSince) " +
            "OR (status = 'ignored' AND received_at > :ignoredSince) " +
            "ORDER BY received_at DESC LIMIT 100"
    )
    fun observeReviewItems(confirmedSince: Long, ignoredSince: Long): Flow<List<NotificationRecordEntity>>

    /**
     * 待审计数（首页红点 / 通知中心角标）。
     *
     * **只数 `parsed`（可审）**：历史遗留的 `raw` 行没有金额/分类，用户审不了，
     * 把它计入角标会让「有个数字在，但点进去无法处理」（P3-4）。`raw` 仍在列表里展示与忽略。
     */
    @Query("SELECT COUNT(*) FROM notification_records WHERE status = 'parsed'")
    fun observePendingCount(): Flow<Int>

    // 忽略/恢复不能清掉重试键，迟到的操作也不能把已确认记录退回待审。
    @Query("UPDATE notification_records SET status = :status, linked_client_id = COALESCE(:clientId, linked_client_id) WHERE id = :id AND (status != 'confirmed' OR :status = 'confirmed')")
    suspend fun updateStatus(id: Long, status: String, clientId: String? = null)

    @Query("SELECT * FROM notification_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NotificationRecordEntity?

    // 只预留幂等键；服务端确认之前不改变待审状态。
    @Query("UPDATE notification_records SET linked_client_id = :clientId WHERE id = :id AND status = 'parsed' AND linked_client_id IS NULL")
    suspend fun reserveConfirmationClientId(id: Long, clientId: String): Int

    @Transaction
    suspend fun claimForConfirmation(id: Long, clientId: String): NotificationRecordEntity? {
        reserveConfirmationClientId(id, clientId)
        return findById(id)
    }

    @Query("SELECT * FROM notification_records WHERE package_name = :packageName AND content = :content AND received_at > :since LIMIT 1")
    suspend fun findDuplicate(packageName: String, content: String, since: Long): NotificationRecordEntity?

    /**
     * 同额去重（跨进程兜底）：查 [dedupWindowSeconds] 内已处理（confirmed 或 parsed）的同金额记录。
     *
     * ⚠️ **不分包名**（旧版叫 `findRecentConfirmedFromOtherChannel`、带 `package_name !=` 排除）：
     * 口径已随「短时同额当作一个账单」对齐——同一家、同一笔消费的多个通知视图
     * （线上：支付宝 `支付成功 ￥65.06…完成` 与 32s 后的 `…付款方式`）必须被当成同一笔。
     *
     * 这一层是内存层失效时唯一的兜底，而**进程被杀正是这个 App 的常态**
     * （无障碍/NLS 服务被 ROM 杀后重连），所以它不能只覆盖跨包名。
     */
    @Query("SELECT * FROM notification_records WHERE parsed_amount = :amount AND status IN ('confirmed', 'parsed') AND received_at >= :since AND received_at <= :until LIMIT 1")
    suspend fun findRecentSameAmount(amount: Int, since: Long, until: Long): NotificationRecordEntity?

    @Query("DELETE FROM notification_records")
    suspend fun deleteAll()
}
