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
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.util.NotificationCorrelator
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationParser
import com.aibill.android.util.NotificationSourceMapping
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

        /** 白名单直接引用 NotificationSourceMapping，添加新包名只需改一处 */
        private val WHITELIST_PACKAGES: Set<String> = NotificationSourceMapping.KNOWN_PACKAGES

        /**
         * 支付/账单特征关键词。用于预筛：命中才可能是账单通知。
         * 策略：宁可放过（放过的不入库不调 AI，成本为 0），也不漏掉真实支付。
         * 覆盖：金额符号/币种、收支动作、银行(尾号/卡号)、理财(收益/利息)、工资报销等。
         */
        val PAYMENT_SIGNAL = Regex(
            "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|" +
            "转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|" +
            "余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"
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
        // 1. 检查系统"通知使用权"是否已授权（跟随系统权限，无 App 内部开关）
        val enabledListeners = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        if (!enabledListeners.contains(packageName)) return

        // 2. 检查包名白名单
        val packageName = sbn.packageName ?: return
        if (packageName !in WHITELIST_PACKAGES) return

        // 3. 提取通知所有文本字段（金额可能在 title/text/bigText 任一处）
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        // 合并全部文本用于解析（去重拼接）
        val fullText = listOf(title, text, bigText, subText, infoText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (fullText.isBlank()) return

        // 4. 去重 (1s 内相同包名+内容)
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        val duplicate = notificationRecordDao.findDuplicate(packageName, fullText, since)
        if (duplicate != null) return

        // 5. 解析通知文本（用合并后的全文）
        val parseResult = notificationParser.parse(packageName, fullText)

        // 5.1 预筛：正则未命中时，若通知无任何支付特征，直接丢弃
        // 避免微信/短信里的普通聊天消息以 raw 状态刷屏通知中心
        if (parseResult == null) {
            val hasPaymentSignal = PAYMENT_SIGNAL.containsMatchIn(fullText)
            if (!hasPaymentSignal) return
        }

        // 6. 通知链关联去重（5分钟窗口内相同金额合并）
        if (parseResult != null) {
            val correlationResult = notificationCorrelator.check(
                packageName = packageName,
                amount = parseResult.amount,
                description = parseResult.description,
                orderId = parseResult.orderId,
            )
            if (correlationResult is NotificationCorrelator.CorrelationResult.Skip) return
        }

        // 7. 智能免确认：提升置信度
        val adjustedConfidence = if (parseResult != null) {
            // 优先使用商家名作 keyword（跨金额/时间稳定），未抽到时降级用 description
            val keyword = (parseResult.merchantName ?: parseResult.description)
                ?.trim()?.lowercase()
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
            title = title.ifBlank { null },
            content = fullText,
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
                        time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                        source = "app_notification",
                        sourceDetail = com.aibill.android.util.NotificationSourceMapping.friendlyName(packageName),
                        syncStatus = "pending",
                        clientCreatedAt = Instant.now().toString()
                    )
                    pendingTransactionDao.insert(pending)
                    SyncScheduler.scheduleSyncIfNeeded(applicationContext)
                    notificationRecordDao.updateStatus(
                        recordId, "confirmed", pending.clientId
                    )
                    // 更新 Widget 数据：原子累加本月收支
                    WidgetDataUpdater.notifyTransactionAdded(
                        context = applicationContext,
                        type = com.aibill.android.domain.model.TransactionType.fromValue(parseResult.type) ?: TransactionType.EXPENSE,
                        amountCents = parseResult.amount,
                        date = pending.date,
                    )
                }
                adjustedConfidence in 60..89 -> {
                    // 中置信度：弹出确认通知
                    val source = com.aibill.android.util.NotificationSourceMapping.friendlyName(packageName)
                    val privacyMode = userPreferences.notificationPrivacy.first()
                    NotificationHelper.showConfirmNotification(
                        context = this,
                        recordId = recordId,
                        amount = parseResult.amount,
                        description = parseResult.description,
                        source = source,
                        privacyMode = privacyMode,
                        type = parseResult.type,
                    )
                }
                // < 60 存待审，已通过 status 实现
            }
        } else if (parseResult == null) {
            // 正则匹配失败，若 AI 开关开启则调 AI 兜底解析
            val aiEnabled = userPreferences.aiParseEnabled.first()
            if (aiEnabled) {
                tryAiParse(fullText, recordId, packageName)
            }
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
     *
     * PR C2：packageName 必须由调用方显式传入。
     * 之前 tryAiParse 是成员函数、内部写裸 packageName 时 Kotlin 解析成
     * Context.packageName（即 App 自己的包名"com.aibill.android"），
     * 导致多条解析的 extras 显示"com.aibill.android"而不是真实来源
     * （微信支付/支付宝等）。
     */
    private suspend fun tryAiParse(text: String, recordId: Long, packageName: String) {
        // 预筛：必须同时包含"数字"和"支付特征"才值得调 AI
        // 避免把微信/短信里的普通聊天消息外发给后端，既省成本也保护隐私
        val hasDigit = text.any { it.isDigit() }
        val hasPaymentSignal = PAYMENT_SIGNAL.containsMatchIn(text)
        if (!hasDigit || !hasPaymentSignal) return

        try {
            val response = aiApi.parse(mapOf("input" to text))
            if (response.code != 0 || response.data == null) return
            val items = response.data.items
            if (items.isEmpty()) return

            // 第一条：写入原始记录（保持原通知记录可见金额/类型）
            val first = items.first()
            notificationRecordDao.updateParsedResult(
                id = recordId,
                amount = first.amount,
                type = first.type,
                description = first.description ?: first.categoryName,
            )

            // 弹窗确认第一条
            val privacyMode = userPreferences.notificationPrivacy.first()
            NotificationHelper.showConfirmNotification(
                context = this@NotificationMonitorService,
                recordId = recordId,
                amount = first.amount,
                description = first.description ?: first.categoryName,
                source = if (items.size > 1) "AI 识别 (${items.size} 笔)" else "AI 识别",
                privacyMode = privacyMode,
                type = first.type,
            )

            // 后续条目（多条账单场景）：各自建一条待确认通知
            // 关联到同一条原始通知记录 ID 以便追溯
            for (i in 1 until items.size) {
                val item = items[i]
                val extraRecordId = notificationRecordDao.insert(
                    NotificationRecordEntity(
                        packageName = packageName,
                        title = null,
                        content = "[关联] $text",
                        parsedAmount = item.amount,
                        parsedType = item.type,
                        parsedDescription = item.description ?: item.categoryName,
                        status = "parsed",
                        receivedAt = System.currentTimeMillis()
                    )
                )
                NotificationHelper.showConfirmNotification(
                    context = this@NotificationMonitorService,
                    recordId = extraRecordId,
                    amount = item.amount,
                    description = item.description ?: item.categoryName,
                    source = "AI 识别 (${i + 1}/${items.size})",
                    privacyMode = privacyMode,
                    type = item.type,
                )
            }
        } catch (e: Exception) {
            // AI 调用失败，静默忽略（通知已存为 raw 待审）
            timber.log.Timber.w(e, "AI 兜底解析失败")
        }
    }
}
