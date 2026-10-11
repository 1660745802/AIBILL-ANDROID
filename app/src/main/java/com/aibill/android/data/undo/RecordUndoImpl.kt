package com.aibill.android.data.undo

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.undo.RecordUndo
import com.aibill.android.domain.undo.UndoOutcome
import com.aibill.android.service.SyncWorker
import com.aibill.android.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 撤销 module 的实现（架构候选 5）。
 *
 * 判据（真值表）见 `domain/undo/RecordUndo` 的 KDoc；本类只负责**兑现**：
 * 读本地行的 `sync_status` → 决定删本地 / 删服务端 / 两侧都不删 → 返回实际结果。
 *
 * 收口前这套判据写在 `AutoRecordActionReceiver.handleUndo`（`internal suspend fun`）里，
 * 为的是让测试绕开 `Hilt_AutoRecordActionReceiver.onReceive` / `goAsync()` 的 `inject()`——
 * 那道门禁要求 Application 是 `@HiltAndroidApp`，本工程的 Robolectric 给不出
 * （`Application::class` 抛 Hilt 前置条件，`AiBillApp::class` 抛 AndroidKeyStore not found，
 * 引入 `hilt-android-testing` 不在本轮范围）。搬移**逐行保留**判据、日志与删除顺序，
 * 唯一变化是「弹 Toast」换成「返回 [UndoOutcome]」，由 receiver 播报。
 *
 * ## 日志 tag 沿用 `AutoRecordActionReceiver`
 *
 * 常量与文案都没改（`db827e4` 刚拆过失败文案），日志 tag 也刻意沿用旧名：
 * 线上排障按 tag 捞历史日志的姿势不必因为搬家而失效。
 */
@Singleton
class RecordUndoImpl @Inject constructor(
    private val pendingTransactionDao: PendingTransactionDao,
    private val transactionRepository: TransactionRepository,
    private val appLogger: AppLogger,
) : RecordUndo {

    override suspend fun undo(clientId: String): UndoOutcome {
        val localRecord = pendingTransactionDao.findByClientId(clientId)

        if (localRecord == null) {
            appLogger.warn(LOG_TAG, "Undo: 本地无此记录 clientId=$clientId")
            return UndoOutcome.LocalMissing
        }

        return when (localRecord.syncStatus) {
            SYNC_STATUS_PENDING -> deleteLocalOnly(clientId, localRecord.syncStatus)

            SYNC_STATUS_FAILED -> {
                // 只有 401 能证明服务端拒绝且未落库；其余 reason 一律不敢动。
                // 用精确相等而非 LIKE 模式：宁可漏删（还能手动处理），不可误删。
                if (localRecord.lastError == SyncWorker.ERROR_TOKEN_EXPIRED) {
                    deleteLocalOnly(clientId, localRecord.syncStatus)
                } else {
                    appLogger.warn(
                        LOG_TAG,
                        "Undo: failed 但 reason 非 401，服务端可能已创建 " +
                            "clientId=$clientId reason=${localRecord.lastError}",
                    )
                    UndoOutcome.ServerStateUnknown
                }
            }

            SYNC_STATUS_SYNCED -> undoOnServer(clientId, localRecord)

            else -> {
                // fail-closed：未知 status 不做任何删除。
                appLogger.warn(
                    LOG_TAG,
                    "Undo: 未知 syncStatus，不删除 clientId=$clientId status=${localRecord.syncStatus}",
                )
                UndoOutcome.ServerStateUnknown
            }
        }
    }

    /**
     * 仅删本地：用于本地 `pending` 或明确被 401 拒绝的 `failed`。
     * `pending` 与在途 SyncWorker 的竞态不由本方法解决，见 [RecordUndo] 真值表。
     */
    private suspend fun deleteLocalOnly(clientId: String, status: String): UndoOutcome {
        pendingTransactionDao.deleteByClientId(clientId)
        appLogger.info(
            LOG_TAG,
            "Undo: 已删除本地未上传记录 clientId=$clientId status=$status",
        )
        return UndoOutcome.Undone
    }

    /** 已上传：删服务端，成功才删本地；失败则保留本地行供用户重试。 */
    private suspend fun undoOnServer(clientId: String, localRecord: PendingTransactionEntity): UndoOutcome {
        val serverId = localRecord.serverTransactionId
        if (serverId == null) {
            // 已上传却没记住 serverId：没有任何可靠凭据定位服务端那条，撤销无法完成。
            // 此时删本地既不达成撤销（服务端那笔仍在，流水页照常显示、可正常删除），
            // 又抹掉「已同步却缺 serverId」唯一的本地痕迹，事后无从对账 —— 故两侧都不动。
            appLogger.warn(LOG_TAG, "Undo: synced 但缺 serverId clientId=$clientId")
            return UndoOutcome.ServerStillHasIt
        }

        return if (transactionRepository.deleteTransaction(serverId) is Result.Success) {
            pendingTransactionDao.deleteByClientId(clientId)
            appLogger.info(
                LOG_TAG,
                "Undo: 已删除服务端记录 serverId=$serverId clientId=$clientId",
            )
            UndoOutcome.Undone
        } else {
            appLogger.warn(LOG_TAG, "Undo: 服务端删除失败 serverId=$serverId")
            UndoOutcome.ServerStillHasIt
        }
    }

    private companion object {
        /** `PendingTransactionEntity.syncStatus` 取值 */
        const val SYNC_STATUS_PENDING = "pending"
        const val SYNC_STATUS_FAILED = "failed"
        const val SYNC_STATUS_SYNCED = "synced"

        /** 沿用收口前的 tag，日志检索姿势不变（见类 KDoc） */
        const val LOG_TAG = "AutoRecordActionReceiver"
    }
}
