package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.presentation.MainActivity
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 自动记账 Heads-up 通知的撤销/查看 Action 处理。
 *
 * - ACTION_UNDO：撤销刚记的账（见 [handleUndo] 的状态真值表）
 * - ACTION_VIEW：打开 App 流水页
 */
@AndroidEntryPoint
class AutoRecordActionReceiver : BroadcastReceiver() {

    @Inject lateinit var pendingTransactionDao: PendingTransactionDao
    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var appLogger: AppLogger

    // PR 修复：注入进程级 ApplicationScope，避免每次 Receiver 实例化都泄漏一个 Scope。
    @Inject @ApplicationScope lateinit var receiverScope: CoroutineScope

    companion object {
        const val ACTION_UNDO = "com.aibill.android.ACTION_AUTO_RECORD_UNDO"
        const val ACTION_VIEW = "com.aibill.android.ACTION_AUTO_RECORD_VIEW"
        const val EXTRA_CLIENT_ID = "extra_client_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** `PendingTransactionEntity.syncStatus` 取值 */
        private const val SYNC_STATUS_PENDING = "pending"
        private const val SYNC_STATUS_FAILED = "failed"
        private const val SYNC_STATUS_SYNCED = "synced"

        /** 真的删掉了（本地或服务端）才允许播报这条 */
        private const val MSG_UNDONE = "已撤销"

        /** 服务端**确有**这笔（synced 但缺凭据 / 服务端删除失败）：流水页数据源是服务端，那里看得到、也删得掉。 */
        private const val MSG_FAILED_MANUAL =
            "撤销失败，请稍后在流水页手动删除"

        /**
         * 服务端**状态未知**（failed 非 401 / 未知 syncStatus）：什么都没删，
         * 既不能主张它在服务器上，本地也没有删除入口。
         */
        private const val MSG_FAILED_UNCHANGED = "撤销失败，未删除任何数据"

        /** 只查了本地行；不主张服务端有没有这笔 */
        private const val MSG_NOT_FOUND = "撤销失败，未找到本地记录"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        when (intent.action) {
            ACTION_UNDO -> {
                // Cancel the notification immediately
                cancelNotification(context, notificationId)

                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        handleUndo(context, clientId)
                    } catch (e: Exception) {
                        appLogger.error("AutoRecordActionReceiver", "Undo failed: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_VIEW -> {
                cancelNotification(context, notificationId)
                openTransactionsPage(context)
            }
        }
    }

    /**
     * 撤销真值表 —— **按已知同步状态分支；不确定状态默认不删。**
     *
     * | 本地行 | 服务端是否可能已有 | 处理 | 用户看到 |
     * |---|---|---|---|
     * | 不存在 | 未知 | 谁都不删（无凭据，不猜） | 未找到本地记录（不指路） |
     * | `pending` | 通常没有；在途 SyncWorker 是已知竞态 | 删本地（保持既有语义） | 已撤销 |
     * | `failed` + 401 (`ERROR_TOKEN_EXPIRED`) | 否（服务端明确拒绝） | 删本地 | 已撤销 |
     * | `failed` + 其它 reason | **可能已经建了** | 谁都不删 | 失败（不指路） |
     * | `synced` + 有 serverId | 是 | 删服务端；成功才删本地 | 已撤销 / 失败（指向流水页） |
     * | `synced` + 无 serverId | 是 | 谁都不删 | 失败（指向流水页） |
     * | 未知 status | 未知 | 谁都不删 | 失败（不指路） |
     *
     * ## 为什么 `failed` 不能一律当「服务端没有」
     *
     * `SyncWorker` 的三条 markFailed 路径里，只有 401 能证明服务端拒绝：
     * - [SyncWorker.ERROR_TOKEN_EXPIRED]：响应是 401，服务端根本没创建 → 可安全删本地。
     * - [SyncWorker.ERROR_SERVER_ANOMALY]：**服务端可能已创建**，只是响应里
     *   `created[]`/`duplicates[]` 都没匹配上 clientId（漏字段/转换异常）。
     * - [SyncWorker.ERROR_MAX_RETRY_EXCEEDED]：本地重试数超限，**上一次请求可能
     *   已经成功、只是响应在网络上丢了**（提交后进程被杀/超时）。
     *   `ERROR_BUSINESS`（5xx）同理。
     *
     * 后三种删本地 = 服务端若已有这笔，就变成「本地删了、服务端还在」的幽灵账。
     * 不删本地的代价是本地那行会一直留着（Home 横幅会计它，且没有逐行删除入口），
     * 但**不能**因此改口说「去流水页删」——这三类里服务端是否真有这笔并不确定。
     * 后者的代价远小于前者，故一律走「不删 + 如实告知未删除任何数据」。
     *
     * ## 为什么不能用 `!= synced` 判据
     *
     * 任何**新增或拼写错误**的 status 都会被 `!= "synced"` 当成「未上传」而删本地，
     * 也就是未来某天改列名/加状态时静默退化成 fail-open。改用白名单 `when`：
     * 未知值落 `else` 分支，默认不删。
     *
     * ## 历史 bug（本次修复）
     *
     * 1. 分支条件只判「本地行是否存在」而不看 `sync_status`，且
     *    `cleanSyncedBefore` 从未被调用（DAO 里定义了但零调用点）、synced 行无限留存，
     *    于是「已上传的账」几乎总是命中本地删除分支 → 服务端账目存活，用户却被告知
     *    「已撤销」（实际连回收站都没进）。
     * 2. 已同步时用 `TransactionQuery(keyword = clientId)` + `items.first()` 猜 serverId：
     *    无排序保证、只取第 1 页、命中多条时删错账。现在只认 `server_transaction_id`
     *    （`SyncWorker.markSynced` → `PendingTransactionDao.markSynced`），宁可失败也不猜。
     *
     * internal 而非 private：便于直接单元测试，绕开 `goAsync()` / ApplicationScope 的时序噪声。
     */
    internal suspend fun handleUndo(context: Context, clientId: String) {
        val localRecord = pendingTransactionDao.findByClientId(clientId)

        if (localRecord == null) {
            appLogger.warn("AutoRecordActionReceiver", "Undo: 本地无此记录 clientId=$clientId")
            showToast(context, MSG_NOT_FOUND)
            return
        }

        when (localRecord.syncStatus) {
            SYNC_STATUS_PENDING -> deleteLocalOnly(context, clientId, localRecord.syncStatus)

            SYNC_STATUS_FAILED -> {
                // 只有 401 能证明服务端拒绝且未落库；其余 reason 一律不敢动。
                // 用精确相等而非 LIKE 模式：宁可漏删（还能手动处理），不可误删。
                if (localRecord.lastError == SyncWorker.ERROR_TOKEN_EXPIRED) {
                    deleteLocalOnly(context, clientId, localRecord.syncStatus)
                } else {
                    appLogger.warn(
                        "AutoRecordActionReceiver",
                        "Undo: failed 但 reason 非 401，服务端可能已创建 " +
                            "clientId=$clientId reason=${localRecord.lastError}",
                    )
                    showToast(context, MSG_FAILED_UNCHANGED)
                }
            }

            SYNC_STATUS_SYNCED -> undoOnServer(context, clientId, localRecord)

            else -> {
                // fail-closed：未知 status 不做任何删除。
                appLogger.warn(
                    "AutoRecordActionReceiver",
                    "Undo: 未知 syncStatus，不删除 clientId=$clientId status=${localRecord.syncStatus}",
                )
                showToast(context, MSG_FAILED_UNCHANGED)
            }
        }
    }

    /**
     * 仅删本地：用于本地 `pending` 或明确被 401 拒绝的 `failed`。
     * `pending` 与在途 SyncWorker 的竞态不由本 helper 解决，见 [handleUndo] 真值表。
     */
    private suspend fun deleteLocalOnly(context: Context, clientId: String, status: String) {
        pendingTransactionDao.deleteByClientId(clientId)
        appLogger.info(
            "AutoRecordActionReceiver",
            "Undo: 已删除本地未上传记录 clientId=$clientId status=$status",
        )
        showToast(context, MSG_UNDONE)
    }

    /** 已上传：删服务端，成功才删本地；失败则保留本地行供用户重试。 */
    private suspend fun undoOnServer(
        context: Context,
        clientId: String,
        localRecord: PendingTransactionEntity,
    ) {
        val serverId = localRecord.serverTransactionId
        if (serverId == null) {
            // 已上传却没记住 serverId：没有任何可靠凭据定位服务端那条，撤销无法完成。
            // 此时删本地既不达成撤销（服务端那笔仍在，流水页照常显示、可正常删除），
            // 又抹掉「已同步却缺 serverId」唯一的本地痕迹，事后无从对账 —— 故两侧都不动。
            appLogger.warn("AutoRecordActionReceiver", "Undo: synced 但缺 serverId clientId=$clientId")
            showToast(context, MSG_FAILED_MANUAL)
            return
        }

        if (transactionRepository.deleteTransaction(serverId) is Result.Success) {
            pendingTransactionDao.deleteByClientId(clientId)
            appLogger.info(
                "AutoRecordActionReceiver",
                "Undo: 已删除服务端记录 serverId=$serverId clientId=$clientId",
            )
            showToast(context, MSG_UNDONE)
        } else {
            appLogger.warn("AutoRecordActionReceiver", "Undo: 服务端删除失败 serverId=$serverId")
            showToast(context, MSG_FAILED_MANUAL)
        }
    }

    private suspend fun showToast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        if (notificationId == -1) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
        NotificationHelper.cancelPendingAutoCancel(notificationId)
    }

    private fun openTransactionsPage(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "VIEW_TRANSACTIONS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }
}
