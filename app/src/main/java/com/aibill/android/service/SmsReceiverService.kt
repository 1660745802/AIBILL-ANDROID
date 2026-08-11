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
    @Inject lateinit var rulesManager: NotificationRulesManager

    /** 缓存的支付特征 Regex + 对应的规则代际，避免每次 SMS 都重新编译 */
    @Volatile
    private var cachedPaymentRegex: Regex = NotificationMonitorService.PAYMENT_SIGNAL
    private var cachedPaymentRegexGeneration: Int = -1

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        appLogger.info("SMS", "短信到达: sender=$sender len=${fullText.length}")

        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
        // 支付特征预筛：缓存 Regex，仅在规则代际变化时重新编译
        val currentGen = rulesManager.getRulesGeneration()
        if (currentGen != cachedPaymentRegexGeneration) {
            cachedPaymentRegex = try {
                Regex(rulesManager.getRules().nls.paymentSignalRegex)
            } catch (e: Exception) {
                Timber.w(e, "SMS: invalid paymentSignalRegex, using PAYMENT_SIGNAL fallback")
                NotificationMonitorService.PAYMENT_SIGNAL
            }
            cachedPaymentRegexGeneration = currentGen
        }

        if (!cachedPaymentRegex.containsMatchIn(text)) {
            appLogger.debug("SMS", "预筛不通过: sender=$sender text=${text.take(40)}")
            return
        }

        appLogger.info("SMS", "预筛通过,交给Processor: sender=$sender len=${text.length}")

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
