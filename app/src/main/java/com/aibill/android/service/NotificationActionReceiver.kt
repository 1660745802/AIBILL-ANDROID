package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * 通知确认弹窗 Action 处理
 * 处理用户点击"确认"或"忽略"按钮的操作
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notificationRecordDao: NotificationRecordDao
    @Inject lateinit var pendingTransactionDao: PendingTransactionDao

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_CONFIRM = "com.aibill.android.ACTION_CONFIRM_RECORD"
        const val ACTION_IGNORE = "com.aibill.android.ACTION_IGNORE_RECORD"
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

        val clientId = UUID.randomUUID().toString()
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val pendingTransaction = PendingTransactionEntity(
            clientId = clientId,
            type = record.parsedType ?: "expense",
            amount = record.parsedAmount ?: 0,
            description = record.parsedDescription,
            date = dateFormat.format(now),
            time = timeFormat.format(now),
            source = "app_notification",
            sourceDetail = com.aibill.android.util.NotificationSourceMapping.friendlyName(record.packageName),
            clientCreatedAt = now.toInstant().toString()
        )

        pendingTransactionDao.insert(pendingTransaction)

        // 更新通知记录：标记已确认 + 关联 clientId
        notificationRecordDao.updateStatus(recordId, "confirmed", clientId)

        // 触发后台同步
        SyncScheduler.scheduleSyncIfNeeded(context)

        // 通知 Widget 刷新：原子累加本月收支
        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = com.aibill.android.domain.model.TransactionType.fromValue(record.parsedType ?: "expense") ?: TransactionType.EXPENSE,
            amountCents = record.parsedAmount ?: 0,
            date = pendingTransaction.date,
        )
    }

    private suspend fun handleIgnore(recordId: Long) {
        notificationRecordDao.updateStatus(recordId, "ignored")
    }
}
