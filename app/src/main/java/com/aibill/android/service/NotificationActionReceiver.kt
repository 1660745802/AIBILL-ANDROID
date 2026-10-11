package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.record.RecordFailure
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 通知确认弹窗 Action 处理
 * 处理用户点击"确认"或"忽略"按钮的操作
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notificationRecordDao: NotificationRecordDao

    /**
     * 入账（记一笔）唯一入口。clientId 生成、送服务端确认、跨表写入、widget、回执、学习
     * 全在里面，本类只负责「读审计记录 → 翻译成入账意图 → 播报结果」与「忽略」动作
     * （架构候选 1 收口，见 `domain/record/RecordIntake`）。
     */
    @Inject lateinit var recordIntake: RecordIntake

    // PR 修复：使用 Hilt 注入的进程级 ApplicationScope，
    // 替代之前"每次实例化 new SupervisorJob+IO"的内存泄漏。
    @Inject @ApplicationScope lateinit var receiverScope: CoroutineScope

    companion object {
        const val ACTION_CONFIRM = "com.aibill.android.ACTION_CONFIRM_RECORD"
        const val ACTION_IGNORE = "com.aibill.android.ACTION_IGNORE_RECORD"
        const val ACTION_CONFIRM_WITH_NOTE = "com.aibill.android.ACTION_CONFIRM_WITH_NOTE"
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (recordId == -1L) return

        cancelNotification(context, notificationId)

        val pendingResult = goAsync()

        when (intent.action) {
            ACTION_CONFIRM -> receiverScope.launch {
                try {
                    handleConfirm(context, recordId)
                } finally {
                    pendingResult.finish()
                }
            }
            ACTION_CONFIRM_WITH_NOTE -> receiverScope.launch {
                try {
                    val note = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(com.aibill.android.util.NotificationHelper.REMOTE_INPUT_KEY)
                        ?.toString()
                    handleConfirmWithNote(context, recordId, note)
                } finally {
                    pendingResult.finish()
                }
            }
            ACTION_IGNORE -> receiverScope.launch {
                try {
                    handleIgnore(recordId)
                } finally {
                    pendingResult.finish()
                }
            }
            else -> pendingResult.finish()
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        if (notificationId == -1) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
        // 取消 10s 自动收起 Runnable，避免无效残留
        NotificationHelper.cancelPendingAutoCancel(notificationId)
    }

    private suspend fun handleConfirm(context: Context, recordId: Long) {
        val record = notificationRecordDao.findById(recordId) ?: return

        // 弹窗直接确认：learn = false。带备注确认见 handleConfirmWithNote（learn = true）。
        // 两者「一个学一个不学」是历史差异，收口只搬运不统一。
        val outcome = recordIntake.intake(
            RecordIntent.NotificationConfirm(
                recordId = recordId,
                packageName = record.packageName,
                amount = record.parsedAmount ?: 0,
                type = record.parsedType ?: "expense",
                description = record.parsedDescription,
                learn = false,
            )
        )
        // 没记上就当场说：弹窗已被 cancelNotification 撤掉，不再报一声用户会以为记上了。
        // 审计行没被改成 confirmed，仍停在待审池（通知中心看得见、可重试）。
        reportFailure(context, outcome.failure)
    }

    private suspend fun handleIgnore(recordId: Long) {
        notificationRecordDao.updateStatus(recordId, "ignored")
    }

    /**
     * 用户通过 RemoteInput 输入备注后确认记账。
     * 流程与 handleConfirm 相同，但用用户输入的 note 覆盖 parsedDescription。
     */
    private suspend fun handleConfirmWithNote(context: Context, recordId: Long, note: String?) {
        val record = notificationRecordDao.findById(recordId) ?: return

        val description = note?.takeIf { it.isNotBlank() } ?: record.parsedDescription

        val outcome = recordIntake.intake(
            RecordIntent.NotificationConfirm(
                recordId = recordId,
                packageName = record.packageName,
                amount = record.parsedAmount ?: 0,
                type = record.parsedType ?: "expense",
                description = description,
                learn = true,
            )
        )
        reportFailure(context, outcome.failure)
    }

    /**
     * 失败必须让用户听见：静默 = 用户以为记上了。成功时由入账 module 自己发轻通知。
     *
     * ⚠️ 必须回到主线程：本协程跑在 `@ApplicationScope`（`Dispatchers.IO`），
     * 而 `Toast.makeText` 在没有 Looper 的线程上会直接抛
     * 「Can't toast on a thread that has not called Looper.prepare()」——
     * 本工程没有全局 `CoroutineExceptionHandler`，那等于崩进程。
     */
    private suspend fun reportFailure(context: Context, failure: RecordFailure?) {
        if (failure == null) return
        withContext(Dispatchers.Main) {
            Toast.makeText(context, failure.userMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
