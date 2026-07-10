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
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        appLogger.info("SMS", "短信到达: sender=$sender len=${fullText.length}")

        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                handleSms(sender, fullText)
            } catch (e: Exception) {
                appLogger.error("SMS", "处理异常: ${e.message}")
                Timber.e(e, "SMS 处理异常")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(sender: String, text: String) {
        // 支付特征预筛
        if (!NotificationMonitorService.PAYMENT_SIGNAL.containsMatchIn(text)) {
            appLogger.debug("SMS", "预筛不通过: sender=$sender")
            return
        }

        appLogger.info("SMS", "预筛通过,交给Processor: sender=$sender")

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