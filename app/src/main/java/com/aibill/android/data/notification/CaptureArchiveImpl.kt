package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureArchive
import com.aibill.android.domain.notification.CapturedText
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureArchive] 的 DAO 实现。
 *
 * 复用 `notification_records`（`status` 是自由字符串）而非新建表 → **无需 Room migration**。
 * `raw` / `auth_pending` 两种状态都不命中「已确认」查询：
 * - `raw` 命中待审列表（用户在通知中心看得见，这正是「不漏账」的落点）；
 * - `auth_pending` 不命中任何 observe* 查询，不会混进通知中心列表。
 */
@Singleton
class CaptureArchiveImpl @Inject constructor(
    private val notificationRecordDao: NotificationRecordDao,
    private val appLogger: AppLogger,
) : CaptureArchive {

    override suspend fun captureUnparsed(capture: Capture, reason: String) {
        if (insert(capture, CaptureArchive.STATUS_RAW)) {
            appLogger.warn(
                "NLS",
                "AI 未解析，落原文待人工确认: reason=$reason pkg=${capture.packageName} " +
                    "text=${capture.fullText.take(60)}"
            )
        }
    }

    override suspend fun captureAuthPending(capture: Capture) {
        insert(capture, CaptureArchive.STATUS_AUTH_PENDING)
    }

    override suspend fun replay(replay: suspend (CapturedText) -> Unit) {
        val since = System.currentTimeMillis() - AUTH_PENDING_TTL_MS
        val pending = notificationRecordDao.findAuthPending(since)
        if (pending.isEmpty()) return
        appLogger.info("NLS", "重放待补偿交易: ${pending.size} 条（登录已恢复）")
        for (record in pending) {
            try {
                replay(
                    CapturedText(
                        id = record.id,
                        packageName = record.packageName,
                        title = record.title.orEmpty(),
                        content = record.content,
                        receivedAt = record.receivedAt,
                    )
                )
            } catch (e: CancellationException) {
                // 取消不是重放失败：不标终态，留给下次重登继续重放；
                // 吞掉会破坏结构化取消（随后的 updateStatus 在已取消协程里也会再抛）。
                throw e
            } catch (e: Exception) {
                appLogger.error("NLS", "待补偿重放异常: ${e.message}")
            }
            // 重放后无论成败都清掉这条队列记录：
            // 成功 → 新的 confirmed/parsed/raw 记录已建立；失败 → 不再无限重试
            notificationRecordDao.updateStatus(record.id, STATUS_AUTH_REPLAYED, null)
        }
    }

    /** 落库失败也不能让异常向上抛崩协程：捕获层的兜底不该自己成为新的崩溃点。 */
    private suspend fun insert(capture: Capture, status: String): Boolean {
        return try {
            notificationRecordDao.insert(
                NotificationRecordEntity(
                    packageName = capture.packageName,
                    title = capture.title.ifBlank { null },
                    content = capture.fullText,
                    status = status,
                    receivedAt = capture.receivedAt,
                )
            )
            true
        } catch (e: Exception) {
            appLogger.error("NLS", "原文捕获写入失败(status=$status): ${e.message}")
            false
        }
    }

    private companion object {
        /** 重放后的终态（不再参与重放） */
        const val STATUS_AUTH_REPLAYED = "auth_replayed"

        /** 待补偿保留时长：超过 24h 的交易 AI 分类已过时，不再自动重放 */
        const val AUTH_PENDING_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
