package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.presentation.MainActivity
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 自动记账 Heads-up 通知的撤销/查看 Action 处理。
 *
 * - ACTION_UNDO：撤销刚记的账（本地未同步则删本地，已同步则调服务端删除）
 * - ACTION_VIEW：打开 App 流水页
 */
@AndroidEntryPoint
class AutoRecordActionReceiver : BroadcastReceiver() {

    @Inject lateinit var pendingTransactionDao: PendingTransactionDao
    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var appLogger: AppLogger

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private suspend fun handleUndo(context: Context, clientId: String) {
        val localRecord = pendingTransactionDao.findByClientId(clientId)

        if (localRecord != null) {
            // Still pending locally — just delete from local DB
            pendingTransactionDao.deleteByClientId(clientId)
            appLogger.info("AutoRecordActionReceiver", "Undo: deleted local pending record clientId=$clientId")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "已撤销，可在回收站中恢复", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Already synced to server — query server by keyword (clientId) to find serverId, then delete
            val searchResult = transactionRepository.getTransactions(keyword = clientId)
            when {
                searchResult is com.aibill.android.domain.model.Result.Success && searchResult.data.items.isNotEmpty() -> {
                    val serverId = searchResult.data.items.first().id
                    if (serverId != null) {
                        when (transactionRepository.deleteTransaction(serverId)) {
                            is com.aibill.android.domain.model.Result.Success -> {
                                appLogger.info("AutoRecordActionReceiver", "Undo: deleted server record serverId=$serverId, clientId=$clientId")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "已撤销，可在回收站中恢复", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is com.aibill.android.domain.model.Result.Error -> {
                                appLogger.warn("AutoRecordActionReceiver", "Undo: server delete failed for serverId=$serverId")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "撤销失败，请稍后在流水页手动删除", Toast.LENGTH_SHORT).show()
                                }
                            }
                            else -> Unit
                        }
                    } else {
                        appLogger.warn("AutoRecordActionReceiver", "Undo: server record has null id for clientId=$clientId")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "撤销失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    appLogger.warn("AutoRecordActionReceiver", "Undo: record not found for clientId=$clientId")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "撤销失败，记录未找到", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
