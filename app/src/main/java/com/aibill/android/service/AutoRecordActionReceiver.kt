package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.undo.RecordUndo
import com.aibill.android.domain.undo.UndoOutcome
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
 * ## 本类只是平台管道（架构候选 5）
 *
 * - `ACTION_UNDO`：撤通知 → `goAsync()` → 问 [RecordUndo] 该怎么办 → 把
 *   [UndoOutcome.userMessage] 播成 Toast；
 * - `ACTION_VIEW`：撤通知 → 打开流水页。
 *
 * 「按 `sync_status` 决定删本地还是删服务端」的真值表与失败文案都在
 * `domain/undo/RecordUndo` / `data/undo/RecordUndoImpl`，那里可构造即可测。
 *
 * 收口前这套判据写在 `internal suspend fun handleUndo` 里、就住在本类中，只为了让单测
 * 绕开 `Hilt_AutoRecordActionReceiver` 在 `onReceive` / `goAsync()` 的 `inject()`——
 * 那道门禁要求 `@HiltAndroidApp` 的 Application，本工程的 Robolectric 给不出。
 * 那是**为测试而开的后门**（逻辑住在壳里、可见性只为测试放宽；另一处同类后门是
 * `ExternalActionReceiver.handleAutoConfirmRecord`，同轮撤掉），现已拆开：
 * 要看判据请看 [RecordUndo] 的 KDoc，要跑用例请看 `RecordUndoImplTest`。
 */
@AndroidEntryPoint
class AutoRecordActionReceiver : BroadcastReceiver() {

    @Inject lateinit var recordUndo: RecordUndo

    /** 收口前它还兼管判据日志；现在判据日志在 `RecordUndoImpl`，这里只兜住「判据抛异常」这一种。 */
    @Inject lateinit var appLogger: AppLogger

    // PR 修复：注入进程级 ApplicationScope，避免每次 Receiver 实例化都泄漏一个 Scope。
    @Inject @ApplicationScope lateinit var receiverScope: CoroutineScope

    companion object {
        const val ACTION_UNDO = "com.aibill.android.ACTION_AUTO_RECORD_UNDO"
        const val ACTION_VIEW = "com.aibill.android.ACTION_AUTO_RECORD_VIEW"
        const val EXTRA_CLIENT_ID = "extra_client_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
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
                        val outcome = recordUndo.undo(clientId)
                        showToast(context, outcome.userMessage)
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
