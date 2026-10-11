package com.aibill.android.data.undo

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.undo.RecordUndo
import com.aibill.android.domain.undo.UndoOutcome
import com.aibill.android.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 撤销 module 的实现（架构候选 5）。
 *
 * 判据（真值表）见 `domain/undo/RecordUndo` 的 KDoc；本类只负责**兑现**：
 * 读本地缓存行的 `sync_status` → 决定删服务端 / 两侧都不删 → 返回实际结果。
 *
 * 缓存里的行**都是服务端已确认的那一笔**（写入时机见 `data/record/RecordIntakeImpl`），
 * 所以 `synced` 分支直接拿 `server_transaction_id` 去删服务端，没有「本地 pending
 * 只删本地」这一档；`synced` 之外的一律 fail-closed：两侧都不删 + 如实说明。
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
            SYNC_STATUS_SYNCED -> undoOnServer(clientId, localRecord)

            else -> {
                // fail-closed：非 synced（老版本遗留的 pending / failed）与未知 status
                // 都没有「服务端一定没有这笔」的证据，一律不动。
                appLogger.warn(
                    LOG_TAG,
                    "Undo: 非已确认缓存行，不删除 clientId=$clientId status=${localRecord.syncStatus}",
                )
                UndoOutcome.ServerStateUnknown
            }
        }
    }

    /** 已确认缓存行：删服务端，成功才删本地；失败则保留本地行供用户重试。 */
    private suspend fun undoOnServer(clientId: String, localRecord: PendingTransactionEntity): UndoOutcome {
        val serverId = localRecord.serverTransactionId
        if (serverId == null) {
            // 已确认却没记住 serverId：没有任何可靠凭据定位服务端那条，撤销无法完成。
            // 此时删本地既不达成撤销（服务端那笔仍在，流水页照常显示、可正常删除），
            // 又抹掉「已确认却缺 serverId」唯一的本地痕迹，事后无从对账 —— 故两侧都不动。
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
        /** 已确认缓存行的唯一取值；其余值（含老版本遗留）一律 fail-closed */
        const val SYNC_STATUS_SYNCED = "synced"

        /** 沿用收口前的 tag，日志检索姿势不变（见类 KDoc） */
        const val LOG_TAG = "AutoRecordActionReceiver"
    }
}
