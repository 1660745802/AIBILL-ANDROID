package com.aibill.android.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.usecase.AutoConfirmSuggester
import com.aibill.android.util.NotificationCorrelator
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * 通知监听服务
 * 监听支付类通知并解析入库
 */
@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var notificationRecordDao: NotificationRecordDao
    @Inject lateinit var pendingTransactionDao: PendingTransactionDao
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var notificationCorrelator: NotificationCorrelator
    @Inject lateinit var autoConfirmSuggester: AutoConfirmSuggester
    @Inject lateinit var aiApi: com.aibill.android.data.remote.api.AiApi

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val DEDUP_WINDOW_MS = 1000L

        private val WHITELIST_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.android.mms",
            "com.google.android.apps.messaging"
        )

        private val SOURCE_NAMES = mapOf(
            "com.tencent.mm" to "微信支付",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.android.mms" to "短信",
            "com.google.android.apps.messaging" to "短信"
        )
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        serviceScope.launch { handleNotification(notification) }
    }

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // 1. 检查全局开关
        val enabled = userPreferences.notificationEnabled.first()
        if (!enabled) return

        // 2. 检查包名白名单
        val packageName = sbn.packageName ?: return
        if (packageName !in WHITELIST_PACKAGES) return

        // 3. 防御性检查 extras
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (text.isNullOrBlank()) return

        // 4. 去重 (1s 内相同包名+内容)
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        val duplicate = notificationRecordDao.findDuplicate(packageName, text, since)
        if (duplicate != null) return

        // 5. 解析通知文本
        val parseResult = notificationParser.parse(packageName, text)

        // 6. 通知链关联去重（5分钟窗口内相同金额合并）
        if (parseResult != null) {
            val correlationResult = notificationCorrelator.check(
                packageName = packageName,
                amount = parseResult.amount,
                description = parseResult.description
            )
            if (correlationResult is NotificationCorrelator.CorrelationResult.Skip) return
        }

        // 7. 智能免确认：提升置信度
        val adjustedConfidence = if (parseResult != null) {
            val keyword = parseResult.description?.trim()?.lowercase()
            val shouldAuto = autoConfirmSuggester.shouldAutoConfirm(
                keyword = keyword,
                amountCents = parseResult.amount
            )
            if (shouldAuto) 95 else parseResult.confidence
        } else {
            null
        }

        // 8. 存入 NotificationRecordEntity
        val entity = NotificationRecordEntity(
            packageName = packageName,
            title = title,
            content = text,
            parsedAmount = parseResult?.amount,
            parsedType = parseResult?.type,
            parsedDescription = parseResult?.description,
            status = determineStatus(adjustedConfidence),
            receivedAt = System.currentTimeMillis()
        )
        val recordId = notificationRecordDao.insert(entity)

        // 9. 根据置信度决定行为
        if (parseResult != null && adjustedConfidence != null) {
            when {
                adjustedConfidence >= 90 -> {
                    // 高置信度：静默入库
                    val pending = PendingTransactionEntity(
                        clientId = UUID.randomUUID().toString(),
                        type = parseResult.type,
                        amount = parseResult.amount,
                        description = parseResult.description,
                        date = LocalDate.now().toString(),
                        source = "app_notification",
                        sourceDetail = text,
                        syncStatus = "pending",
                        clientCreatedAt = Instant.now().toString()
                    )
                    pendingTransactionDao.insert(pending)
                    SyncScheduler.scheduleSyncIfNeeded(applicationContext)
                    notificationRecordDao.updateStatus(
                        recordId, "confirmed", pending.clientId
                    )
                    // 更新 Widget 数据
                    WidgetDataUpdater.notifyTransactionAdded(applicationContext)
                }
                adjustedConfidence in 60..89 -> {
                    // 中置信度：弹出确认通知
                    val source = SOURCE_NAMES[packageName] ?: packageName
                    val privacyMode = userPreferences.notificationPrivacy.first()
                    NotificationHelper.showConfirmNotification(
                        context = this,
                        recordId = recordId,
                        amount = parseResult.amount,
                        description = parseResult.description,
                        source = source,
                        privacyMode = privacyMode
                    )
                }
                // < 60 存待审，已通过 status 实现
            }
        } else if (parseResult == null) {
            // 正则匹配失败，调 AI 兜底解析
            tryAiParse(text, recordId)
        }
    }

    private fun determineStatus(confidence: Int?): String {
        return when {
            confidence == null -> "raw"
            confidence >= 60 -> "parsed"
            else -> "raw"
        }
    }

    /**
     * 正则匹配失败时调用 AI 兜底解析
     * 将通知文本发给后端 AI，如果解析出结果则弹窗确认
     */
    private suspend fun tryAiParse(text: String, recordId: Long) {
        try {
            val response = aiApi.parse(mapOf("input" to text))
            if (response.code != 0 || response.data == null) return
            val items = response.data.items
            if (items.isEmpty()) return

            val first = items.first()
            // AI 解析成功，更新通知记录
            notificationRecordDao.updateStatus(recordId, "parsed")

            // 弹窗让用户确认（AI 解析置信度默认中等）
            val privacyMode = userPreferences.notificationPrivacy.first()
            NotificationHelper.showConfirmNotification(
                context = this@NotificationMonitorService,
                recordId = recordId,
                amount = first.amount,
                description = first.description ?: first.categoryName,
                source = "AI 识别",
                privacyMode = privacyMode
            )
        } catch (e: Exception) {
            // AI 调用失败，静默忽略（通知已存为 raw 待审）
            timber.log.Timber.w(e, "AI 兜底解析失败")
        }
    }
}
