package com.aibill.android.service

import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureDropHandler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.CaptureIngress
import com.aibill.android.domain.notification.SourceChannel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * SMS 短信接收器——**平台翻译 adapter**（C02 后）。
 *
 * 只做：把 `SmsMessage[]` 翻译成 [Capture]、记录本渠道的捕获层早退计数、把 Capture
 * 交给 [CaptureIngress]，并用返回的 `Job` 挂上 `goAsync` 的 `finish()`。
 * 预筛、取消/异常处理、入账接线全部收在 ingress 后面。
 *
 * receipt（`CaptureFunnelStats.RECEIVED_SMS`）仍在 adapter：它发生在「空短信」
 * 早退之前，收进 ingress 会漏计那些事件（破坏捕获层漏斗顶端）。
 */
@AndroidEntryPoint
class SmsReceiverService : BroadcastReceiver() {

    @Inject lateinit var ingress: CaptureIngress
    @Inject lateinit var captureFunnelStats: CaptureFunnelStats
    @Inject lateinit var captureDropHandler: CaptureDropHandler
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    /**
     * 归因包名（与审计记录同口径）：`sms:<发件号码>`。
     *
     * 拿不到号码的早退（非短信广播 / 空数组）用固定占位：服务端 `package` 必填，
     * 而「哪个号码」在这两个分支里本就无从得知。
     */
    private fun smsPackageOf(sender: String?): String =
        "sms:" + sender.orEmpty().ifBlank { UNKNOWN_SENDER }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            captureDropHandler.onDrop(CaptureDrop.SMS_NOT_ACTION, smsPackageOf(null))
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            captureDropHandler.onDrop(CaptureDrop.SMS_NO_MESSAGES, smsPackageOf(null))
            return
        }

        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        captureFunnelStats.recordReceived(SourceChannel.SMS)
        if (fullText.isBlank()) {
            // 预筛在空正文之前就会被 return 掉，所以这一层必须在捕获层计（跟 NLS 不同）
            captureDropHandler.onDrop(CaptureDrop.SMS_BLANK_TEXT, smsPackageOf(sender), rawText = fullText)
            return
        }

        appLogger.info("SMS", "短信到达: sender=$sender len=${fullText.length}")

        // goAsync 延长广播接收的有效窗口；ingress 的任务完成（含异常）即 finish。
        val pendingResult = goAsync()
        ingress.admit(
            Capture(
                channel = SourceChannel.SMS,
                // 与审计记录同口径：短信用 `sms:<发件号码>` 当来源，不与 NLS 的包名混在一起
                packageName = "sms:$sender",
                title = sender,
                fullText = fullText,
                receivedAt = System.currentTimeMillis(),
            )
        ).invokeOnCompletion { pendingResult.finish() }
    }

    private companion object {
        /** 发件号码不可得时的占位（仅用于早退样本归因）。 */
        const val UNKNOWN_SENDER = "unknown"
    }
}
