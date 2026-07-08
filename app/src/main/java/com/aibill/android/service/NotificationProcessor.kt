package com.aibill.android.service

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.util.AiResultValidator
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationSourceMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知处理中心（v4）
 *
 * 三渠道统一入口（NLS / A11Y / SMS），流程：
 *   1. AI 解析（单条通知，短文本，不再合并）
 *   2. 解析成功后，按 (amount, type) + 60s 时间窗在 DB 层去重
 *   3. 去重通过 → 入库 + 触发同步 + 轻通知
 *   4. 去重命中 → 静默丢弃
 *
 * 设计要点：
 * - 客户端不做文本合并，每条通知单独调 AI，避免「A11Y 全支付页 + NLS 短确认」合出 800 字把后端打挂
 * - 去重放在 AI 之后，用语义级（金额）做判重，避免「¥ vs ￥」这种字符级漏判
 * - 入库路径用 Mutex 串行化，杜绝 NLS/A11Y/SMS 同时调 AI 同时查 DB 同时 insert 的竞态
 */
@Singleton
class NotificationProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiApi: AiApi,
    private val aiResultValidator: AiResultValidator,
    private val notificationRecordDao: NotificationRecordDao,
    private val pendingTransactionDao: PendingTransactionDao,
    private val userPreferences: UserPreferences,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val appLogger: AppLogger,
) {

    /** 单条通知的输入项。三渠道（NLS/A11Y/SMS）各自构造，扔进 [process] 即可 */
    data class Item(
        val packageName: String,
        val title: String,
        val fullText: String,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    companion object {
        /** 后置去重窗口：同 amount + 60s 内视为同一笔（跨渠道冗余通知） */
        private const val DEDUP_WINDOW_MS = 60_000L
    }

    /** 入库路径串行化：dedup-check + insert 必须原子，否则会双写 */
    private val insertMutex = Mutex()

    /**
     * 处理一条通知：调 AI → 校验 → 后置按金额去重 → 入库 / 入待审
     */
    suspend fun process(item: Item) {
        val aiEnabled = userPreferences.aiParseEnabled.first()
        if (!aiEnabled) {
            appLogger.info("NLS", "AI关闭，丢弃: ${item.fullText.take(30)}")
            return
        }

        val learnedCategoryId = run {
            val keyword = (item.title.takeIf { it.isNotBlank() } ?: item.fullText.take(30))
                .trim().lowercase()
            categoryLearningEngine.matchCategory(keyword)
        }

        try {
            appLogger.info("NLS", "调AI: pkg=${item.packageName} text=${item.fullText}")
            val response = aiApi.parse(mapOf("input" to item.fullText))
            if (response.code != 0 || response.data == null) {
                appLogger.warn("NLS", "AI失败: code=${response.code}")
                return
            }
            val items = response.data.items
            if (items.isEmpty()) {
                appLogger.info("NLS", "AI判定非支付，丢弃")
                return
            }

            val aiItem = items.maxByOrNull { aiItemScore(it) } ?: return

            val validation = aiResultValidator.validate(
                amount = aiItem.amount,
                type = aiItem.type,
                categoryId = aiItem.categoryId,
                description = aiItem.description,
            )

            val finalCategoryId = learnedCategoryId ?: aiItem.categoryId

            val isComplete = aiItem.amount > 0 &&
                aiItem.type in setOf("expense", "income", "transfer") &&
                validation.isValid &&
                (learnedCategoryId != null || (
                    aiItem.categoryId != null &&
                    !aiItem.categoryName.isNullOrBlank() &&
                    aiItem.categoryName.lowercase() !in setOf("其他", "其它", "未分类", "other")
                ))

            if (isComplete) {
                val inserted = insertMutex.withLock {
                    val recentDuplicate = notificationRecordDao.findRecentConfirmedFromOtherChannel(
                        aiItem.amount, item.receivedAt - DEDUP_WINDOW_MS, item.packageName
                    )
                    if (recentDuplicate != null) {
                        appLogger.debug("NLS", "DB跨渠道去重: 60s内其他渠道已入库同金额=${aiItem.amount} otherPkg=${recentDuplicate.packageName}")
                        false
                    } else {
                        directInsert(
                            item = item,
                            amount = aiItem.amount,
                            type = aiItem.type,
                            categoryId = finalCategoryId,
                            description = aiItem.description ?: aiItem.categoryName,
                            source = if (learnedCategoryId != null) "learning+ai" else "ai",
                        )
                        true
                    }
                }
                if (inserted) {
                    appLogger.info("NLS", "✓入库: ¥${"%.2f".format(aiItem.amount/100.0)} ${aiItem.description ?: aiItem.categoryName} type=${aiItem.type}")
                    val learnKey = (aiItem.description ?: aiItem.categoryName)?.trim()
                    if (!learnKey.isNullOrBlank() && finalCategoryId != null) {
                        categoryLearningEngine.learnFromCorrection(learnKey, finalCategoryId)
                    }
                }
            } else {
                appLogger.info("NLS", "→待审: amount=${aiItem.amount} type=${aiItem.type} cat=${aiItem.categoryName}")
                insertPendingReview(item, aiItem)
            }
        } catch (e: Exception) {
            appLogger.error("NLS", "AI异常: ${e.message}")
            Timber.w(e, "AI 解析失败，丢弃: ${item.packageName}")
        }
    }

    /**
     * AI 返回多条时选最优：按信息完整度评分。
     * 有金额+1，有明确类型+1，有分类(非"其他")+2，有描述+1
     */
    private fun aiItemScore(item: AiParsedItemDto): Int {
        var score = 0
        if (item.amount > 0) score += 1
        if (item.type in setOf("expense", "income", "transfer")) score += 1
        if (item.categoryId != null && !item.categoryName.isNullOrBlank() &&
            item.categoryName.lowercase() !in setOf("其他", "其它", "未分类", "other")) score += 2
        if (!item.description.isNullOrBlank()) score += 1
        return score
    }

    /**
     * 信息完整，直接入库 + 发轻通知
     */
    private suspend fun directInsert(
        item: Item,
        amount: Int,
        type: String,
        categoryId: Int?,
        description: String?,
        source: String,
    ) {
        val clientId = UUID.randomUUID().toString()
        val now = LocalDate.now().toString()
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

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

        notificationRecordDao.updateStatus(recordId, "confirmed", clientId)

        SyncScheduler.scheduleSyncIfNeeded(context)

        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
            amountCents = amount,
            date = now,
        )

        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showAutoRecordedNotification(
            context = context,
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
        item: Item,
        aiItem: AiParsedItemDto,
    ) {
        val recordId = notificationRecordDao.insert(
            NotificationRecordEntity(
                packageName = item.packageName,
                title = item.title.ifBlank { null },
                content = item.fullText,
                parsedAmount = aiItem.amount.takeIf { it > 0 },
                parsedType = aiItem.type.takeIf { it in setOf("expense", "income", "transfer") },
                parsedDescription = aiItem.description ?: aiItem.categoryName,
                status = "parsed",
                receivedAt = item.receivedAt,
            )
        )

        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showConfirmNotification(
            context = context,
            recordId = recordId,
            amount = aiItem.amount.takeIf { it > 0 } ?: 0,
            description = aiItem.description ?: aiItem.categoryName,
            source = NotificationSourceMapping.friendlyName(item.packageName),
            privacyMode = privacyMode,
            type = aiItem.type,
        )
    }
}