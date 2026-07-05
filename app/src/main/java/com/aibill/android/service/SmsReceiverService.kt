package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.util.BankSmsPatterns
import com.aibill.android.util.NotificationCorrelator
import com.aibill.android.util.NotificationHelper
import com.aibill.android.data.local.datastore.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * SMS 短信接收器
 *
 * 监听银行短信（RECEIVE_SMS），解析交易信息并入库。
 * 与 NotificationMonitorService 共用去重逻辑（NotificationCorrelator），
 * 同一笔交易不会因为同时收到通知和短信而重复记录。
 */
@AndroidEntryPoint
class SmsReceiverService : BroadcastReceiver() {

    @Inject lateinit var notificationRecordDao: NotificationRecordDao
    @Inject lateinit var bankSmsPatterns: BankSmsPatterns
    @Inject lateinit var notificationCorrelator: NotificationCorrelator
    @Inject lateinit var userPreferences: UserPreferences

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // 合并多段短信为完整文本
        val fullText = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullText.isBlank()) return

        Timber.d("SMS received: sender=$sender, text=${fullText.take(50)}...")

        val pendingResult = goAsync()

        receiverScope.launch {
            try {
                handleSms(context, sender, fullText)
            } catch (e: Exception) {
                Timber.e(e, "SMS 处理异常")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSms(context: Context, sender: String, text: String) {
        // 1. 银行短信格式匹配
        val parseResult = bankSmsPatterns.parse(sender, text) ?: return

        // 2. 通知关联去重（同一笔交易可能通知+短信都到）
        val correlationResult = notificationCorrelator.check(
            packageName = "sms:$sender",
            amount = parseResult.amount,
            description = parseResult.description,
            orderId = parseResult.orderId,
        )
        if (correlationResult is NotificationCorrelator.CorrelationResult.Skip) {
            Timber.d("SMS 去重: 跳过重复短信 sender=$sender amount=${parseResult.amount}")
            return
        }

        // 3. 存入 NotificationRecordEntity（复用通知中心展示）
        val entity = NotificationRecordEntity(
            packageName = "sms:$sender",
            title = parseResult.bankName,
            content = text,
            parsedAmount = parseResult.amount,
            parsedType = parseResult.type,
            parsedDescription = parseResult.description,
            status = "parsed",
            receivedAt = System.currentTimeMillis()
        )
        val recordId = notificationRecordDao.insert(entity)

        // 4. 弹窗确认（银行短信一律弹窗，不静默入库，保证准确率）
        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showConfirmNotification(
            context = context,
            recordId = recordId,
            amount = parseResult.amount,
            description = parseResult.description,
            source = parseResult.bankName ?: "银行短信",
            privacyMode = privacyMode,
            type = parseResult.type,
        )
    }
}
