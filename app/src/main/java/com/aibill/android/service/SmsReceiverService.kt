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
 * SMS 短信接收器 (v4)
 *
 * 监听银行短信（RECEIVE_SMS），交给 NotificationProcessor 统一处理。
 * 不再走 NotificationBuffer 合并池——v4 各渠道独立调 AI，按金额后置去重。
 */
@AndroidEntryPoint
class SmsReceiverService : BroadcastReceiver() {

    @Inject lateinit var notificationProcessor: NotificationProcessor
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

    private suspend fun handleSms(sender: String, text: String) {
        // 支付特征预筛
        if (!NotificationMonitorService.PAYMENT_SIGNAL.containsMatchIn(text)) return

        // 直接交给 Processor（AI + 后置按金额去重）
        notificationProcessor.process(
            NotificationProcessor.Item(
                packageName = "sms:$sender",
                title = sender,
                fullText = text,
                channel = NotificationProcessor.Channel.SMS,
            )
        )
    }
}