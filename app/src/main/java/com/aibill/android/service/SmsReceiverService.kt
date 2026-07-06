package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.aibill.android.util.NotificationParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * SMS 短信接收器 (v3)
 *
 * 监听银行短信（RECEIVE_SMS），入统一 NotificationBuffer。
 * 由 Buffer 去重后交给 AI 解析，与通知渠道共享同一套处理逻辑。
 * 不再单独入库或弹窗——所有渠道统一由 processBatch 处理。
 */
@AndroidEntryPoint
class SmsReceiverService : BroadcastReceiver() {

    @Inject lateinit var notificationBuffer: NotificationBuffer
    @Inject lateinit var notificationParser: NotificationParser

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        Timber.d("SMS received: sender=$sender, text=${fullText.take(50)}...")

        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                handleSms(sender, fullText)
            } catch (e: Exception) {
                Timber.e(e, "SMS 处理异常")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSms(sender: String, text: String) {
        // 支付特征预筛（银行短信几乎都有，但过滤营销短信）
        if (!NotificationMonitorService.PAYMENT_SIGNAL.containsMatchIn(text)) return

        // 快速提取金额用于去重
        val roughAmount = notificationParser.extractAmountOnly(text)

        // 入统一 Buffer（与通知渠道共享去重 + AI 处理）
        notificationBuffer.enqueue(
            item = NotificationBuffer.BufferedItem(
                packageName = "sms:$sender",
                title = sender,
                fullText = text,
                roughAmount = roughAmount,
            ),
            scope = receiverScope,
            onFlush = { /* globalFlushHandler 处理 */ },
        )
    }
}
