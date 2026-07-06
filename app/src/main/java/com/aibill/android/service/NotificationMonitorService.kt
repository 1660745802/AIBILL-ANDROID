package com.aibill.android.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.util.AiResultValidator
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
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * 通知监听服务 v3
 *
 * 架构：排除层 → 缓冲去重 → AI 主力解析 → 入库/待审
 * 核心原则：
 * - AI 是唯一裁判，本地只排除明显垃圾
 * - 正则仅用于去重时提取金额，永远不面对用户
 * - 用户看到的 = AI 返回了结果的
 */
@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var notificationRecordDao: NotificationRecordDao
    @Inject lateinit var pendingTransactionDao: PendingTransactionDao
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var categoryLearningEngine: CategoryLearningEngine
    @Inject lateinit var aiApi: com.aibill.android.data.remote.api.AiApi
    @Inject lateinit var aiResultValidator: AiResultValidator
    @Inject lateinit var notificationBuffer: NotificationBuffer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val DEDUP_WINDOW_MS = 1000L

        /** 白名单：只处理这些包名的通知 */
        private val WHITELIST_PACKAGES: Set<String> = NotificationSourceMapping.KNOWN_PACKAGES

        /**
         * 支付特征关键词，用于排除层判断微信/支付宝通知是否可能是账务。
         * 银行App/短信App 不需要此判断（全部放行）。
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
        // 注册全局 flush 处理器，所有渠道（通知/短信/无障碍）共享
        notificationBuffer.globalFlushHandler = ::processBatch
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationBuffer.globalFlushHandler = null
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        serviceScope.launch { handleNotification(notification) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // v3: 不再做通知撤回降级，AI 入库的就是对的
    }

    // ═══════════════════════════════════════════════════════════════
    // L1: 排除层 → L2: 入缓冲
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // 1. 包名白名单
        val packageName = sbn.packageName ?: return
        if (packageName !in WHITELIST_PACKAGES) return

        // 2. 提取通知文本
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        val fullText = listOf(title, text, bigText, subText, infoText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (fullText.isBlank()) return

        // 3. 排除层：过滤明显不是账务的通知
        if (!isLikelyFinancial(packageName, title, fullText)) {
            Timber.d("排除层过滤: pkg=$packageName, title=$title")
            return
        }

        // 4. 1s 内容去重（防同一条通知被系统多次分发）
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        val duplicate = notificationRecordDao.findDuplicate(packageName, fullText, since)
        if (duplicate != null) return

        // 5. 入缓冲池（60s 后统一处理）
        val roughAmount = notificationParser.extractAmountOnly(fullText)
        notificationBuffer.enqueue(
            item = NotificationBuffer.BufferedItem(
                packageName = packageName,
                title = title,
                fullText = fullText,
                roughAmount = roughAmount,
            ),
            scope = serviceScope,
            onFlush = ::processBatch,
        )
    }

    /**
     * 排除层：判断通知是否"可能是账务"。
     * 返回 false = 100% 不是账务，直接丢弃。
     * 返回 true = 不确定，交给 AI 判断。
     * 设计原则：极保守，宁可多放不漏。
     */
    private fun isLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
        return when (packageName) {
            "com.tencent.mm" -> {
                // 微信：title 是"微信支付"直接放行
                if (title == "微信支付" || title == "微信支付凭证" || title.contains("零钱")) return true
                // 否则看全文有没有支付特征
                PAYMENT_SIGNAL.containsMatchIn(fullText)
            }
            "com.eg.android.AlipayGphone" -> {
                if (title.contains("支付") || title.contains("账单") ||
                    title.contains("花呗") || title.contains("余额") ||
                    title.contains("到账") || title.contains("收款")) return true
                PAYMENT_SIGNAL.containsMatchIn(fullText)
            }
            else -> {
                // 银行/短信等白名单 App → 全部放行
                true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // L3 + L4: 批量处理（学习引擎 → AI → 入库/待审/丢弃）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 缓冲池 flush 后的回调。接收去重后的通知列表，逐条处理。
     */
    private suspend fun processBatch(batch: List<NotificationBuffer.BufferedItem>) {
        val aiEnabled = userPreferences.aiParseEnabled.first()

        for (item in batch) {
            try {
                processOneNotification(item, aiEnabled)
            } catch (e: Exception) {
                Timber.w(e, "处理通知异常: ${item.packageName}")
            }
        }
    }

    private suspend fun processOneNotification(
        item: NotificationBuffer.BufferedItem,
        aiEnabled: Boolean,
    ) {
        // ① 学习引擎预匹配分类（但不直接入库，金额/类型仍由 AI 给出）
        val keyword = (item.title.takeIf { it.isNotBlank() } ?: item.fullText.take(30)).trim().lowercase()
        val learnedCategoryId = categoryLearningEngine.matchCategory(keyword)

        // ② AI 解析（必须走 AI 获取准确的金额和类型）
        if (!aiEnabled) return // AI 关闭 → 丢弃

        try {
            val response = aiApi.parse(mapOf("input" to item.fullText))
            if (response.code != 0 || response.data == null) return // AI 失败 → 丢弃
            val items = response.data.items
            if (items.isEmpty()) return // AI 判定不是支付 → 丢弃

            // 逐条处理 AI 返回的交易
            for (aiItem in items) {
                val validation = aiResultValidator.validate(
                    amount = aiItem.amount,
                    type = aiItem.type,
                    categoryId = aiItem.categoryId,
                    description = aiItem.description,
                )

                // 用学习引擎的分类覆盖 AI 的（如果学习引擎有命中）
                val finalCategoryId = learnedCategoryId ?: aiItem.categoryId
                val finalCategoryName = if (learnedCategoryId != null) null else aiItem.categoryName

                // 判断是否信息完整
                val isComplete = aiItem.amount > 0 &&
                    aiItem.type in setOf("expense", "income", "transfer") &&
                    validation.isValid &&
                    // 分类必须明确（非"其他"/null），学习引擎命中也算明确
                    (learnedCategoryId != null || (
                        aiItem.categoryId != null &&
                        !aiItem.categoryName.isNullOrBlank() &&
                        aiItem.categoryName.lowercase() !in setOf("其他", "其它", "未分类", "other")
                    ))

                if (isComplete) {
                    // 入库前 DB 去重：同包名+同金额+60s 内已有 confirmed → 跳过（防无障碍+通知重复）
                    val recentDuplicate = notificationRecordDao.findRecentConfirmed(
                        item.packageName, aiItem.amount, item.receivedAt - 60_000L
                    )
                    if (recentDuplicate != null) {
                        Timber.d("入库去重：跳过重复 pkg=${item.packageName} amount=${aiItem.amount}")
                        continue
                    }

                    // 信息完整 → 直接入库 + 轻通知
                    directInsert(
                        item = item,
                        amount = aiItem.amount,
                        type = aiItem.type,
                        categoryId = finalCategoryId,
                        description = aiItem.description ?: aiItem.categoryName,
                        source = if (learnedCategoryId != null) "learning+ai" else "ai",
                    )
                    // 触发学习：记住商家→分类+类型映射
                    val learnKey = (aiItem.description ?: aiItem.categoryName)?.trim()
                    if (!learnKey.isNullOrBlank() && finalCategoryId != null) {
                        categoryLearningEngine.learnFromCorrection(learnKey, finalCategoryId)
                    }
                } else {
                    // AI 有结果但信息不完整 → 进待审池 + 发确认通知
                    insertPendingReview(item, aiItem)
                }
            }
        } catch (e: Exception) {
            // AI 调用失败（网络/超时）→ 丢弃，不面对用户
            Timber.w(e, "AI 解析失败，丢弃: ${item.packageName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 入库方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 信息完整，直接入库 + 发轻通知
     */
    private suspend fun directInsert(
        item: NotificationBuffer.BufferedItem,
        amount: Int,
        type: String,
        categoryId: Int?,
        description: String?,
        source: String,
    ) {
        val clientId = UUID.randomUUID().toString()
        val now = LocalDate.now().toString()
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

        // 存通知记录（追溯用）
        val recordId = notificationRecordDao.insert(
            NotificationRecordEntity(
                packageName = item.packageName,
                title = item.title.ifBlank { null },
                content = item.fullText,
                parsedAmount = amount,
                parsedType = type,
                parsedDescription = description,
                status = "confirmed",
                receivedAt = item.receivedAt,
            )
        )

        // 存待同步交易
        val pending = PendingTransactionEntity(
            clientId = clientId,
            type = type,
            amount = amount,
            categoryId = categoryId,
            description = description,
            date = now,
            time = time,
            source = "app_notification",
            sourceDetail = NotificationSourceMapping.friendlyName(item.packageName),
            syncStatus = "pending",
            clientCreatedAt = Instant.now().toString(),
        )
        pendingTransactionDao.insert(pending)

        // 关联记录
        notificationRecordDao.updateStatus(recordId, "confirmed", clientId)

        // 触发同步
        SyncScheduler.scheduleSyncIfNeeded(applicationContext)

        // Widget 更新
        WidgetDataUpdater.notifyTransactionAdded(
            context = applicationContext,
            type = TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
            amountCents = amount,
            date = now,
        )

        // 发轻通知（无按钮，5s 消失）
        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showAutoRecordedNotification(
            context = this,
            recordId = recordId,
            amount = amount,
            description = description,
            source = NotificationSourceMapping.friendlyName(item.packageName),
            type = type,
            privacyMode = privacyMode,
        )
    }

    /**
     * AI 有结果但信息不完整 → 进待审池 + 发确认通知
     */
    private suspend fun insertPendingReview(
        item: NotificationBuffer.BufferedItem,
        aiItem: com.aibill.android.data.remote.dto.response.AiParsedItemDto,
    ) {
        val recordId = notificationRecordDao.insert(
            NotificationRecordEntity(
                packageName = item.packageName,
                title = item.title.ifBlank { null },
                content = item.fullText,
                parsedAmount = aiItem.amount.takeIf { it > 0 },
                parsedType = aiItem.type.takeIf { it in setOf("expense", "income", "transfer") },
                parsedDescription = aiItem.description ?: aiItem.categoryName,
                status = "parsed", // 待审
                receivedAt = item.receivedAt,
            )
        )

        // 发确认通知（有按钮，让用户补充信息）
        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showConfirmNotification(
            context = this,
            recordId = recordId,
            amount = aiItem.amount.takeIf { it > 0 } ?: 0,
            description = aiItem.description ?: aiItem.categoryName,
            source = NotificationSourceMapping.friendlyName(item.packageName),
            privacyMode = privacyMode,
            type = aiItem.type,
        )
    }
}
