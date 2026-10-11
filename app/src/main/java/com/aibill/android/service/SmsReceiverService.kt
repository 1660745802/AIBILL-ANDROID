package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * SMS 短信接收器——**平台翻译 adapter**。
 *
 * 收口前它只跑自己的 2 步预筛（支付特征 + 营销词）：同一段促销/日报文案从短信进来
 * 会直接送去调 AI，而从通知进来会被 `NotificationTextClassifier` 拦下。现在走
 * [NotificationPrefilter]，与 NLS/A11Y 同一套判据。
 *
 * 不再走 NotificationBuffer 合并池——各渠道独立调 AI，按金额后置去重。
 */
@AndroidEntryPoint
class SmsReceiverService : BroadcastReceiver() {

    @Inject lateinit var notificationProcessor: NotificationProcessor
    @Inject lateinit var prefilter: NotificationPrefilter
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    // PR 修复：注入进程级 ApplicationScope，避免每次 SMS 接收都泄漏一个 SupervisorJob。
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        appLogger.info("SMS", "短信到达: sender=$sender len=${fullText.length}")

        val pendingResult = goAsync()

        scope.launch {
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
        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.SMS,
                // 与审计记录同口径：短信用 `sms:<发件号码>` 当来源，不与 NLS 的包名混在一起
                packageName = "sms:$sender",
                title = sender,
                fullText = text,
                receivedAt = System.currentTimeMillis(),
            )
        )

        if (verdict !is Verdict.Pass) return
        val passed = verdict.capture

        appLogger.info("SMS", "预筛通过,交给Processor: sender=$sender len=${passed.fullText.length}")

        // 直接交给 Processor（AI + 评分窗口 + 跨渠道金额去重）
        notificationProcessor.process(
            NotificationProcessor.Item(
                packageName = passed.packageName,
                title = passed.title,
                fullText = passed.fullText,
                channel = NotificationProcessor.Channel.SMS,
                receivedAt = passed.receivedAt,
            )
        )
    }
}
