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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /** 来源渠道 */
    enum class Channel { NLS, A11Y, SMS }

    /** 单条通知的输入项。三渠道各自构造，扔进 [process] 即可 */
    data class Item(
        val packageName: String,
        val title: String,
        val fullText: String,
        val channel: Channel = Channel.NLS,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    companion object {
        /** 后置去重窗口：同 amount + 60s 内视为同一笔（跨渠道冗余通知） */
        private const val DEDUP_WINDOW_MS = 60_000L
        /** 评分窗口：10s 内同金额的 AI 结果比较 score，取最优入库 */
        private const val SCORE_WINDOW_MS = 10_000L
    }

    /** 入库路径串行化：dedup-check + insert 必须原子，否则会双写 */
    private val insertMutex = Mutex()

    /** 评分窗口池：10s 内收集同金额的 AI 结果，取最优 */
    private data class ScoredCandidate(
        val item: Item,
        val amount: Int,
        val type: String,
        val categoryId: Int?,
        val categoryName: String?,
        val categoryIcon: String?,
        val description: String?,
        val source: String,
        val score: Int,
        val isComplete: Boolean,
        val receivedAt: Long,
    )
    private val scoringPool = java.util.concurrent.ConcurrentHashMap<Int, ScoredCandidate>() // key=amount
    private val scoringJobs = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val processorScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** 内存级已入库记录（用于跨渠道去重，比 DB 查询更快更准） */
    private data class ProcessedEntry(val amount: Int, val channel: Channel, val packageName: String, val time: Long)
    private val recentProcessed = java.util.concurrent.ConcurrentLinkedDeque<ProcessedEntry>()

    /**
     * 跨渠道去重判断（内存级）：
     * - 不同 channel 或不同包名 + 同金额 + 60s 内 → 重复
     * - 同 channel 同包名 → 不去重（视为真实两笔交易）
     */
    private fun isDuplicateAcrossChannels(amount: Int, channel: Channel, packageName: String, now: Long): Boolean {
        // 先清过期
        val cutoff = now - DEDUP_WINDOW_MS
        recentProcessed.removeIf { it.time < cutoff }
        // 查重：不同渠道 或 不同包名（同金额60s内）
        return recentProcessed.any {
            it.amount == amount &&
            (it.channel != channel || it.packageName != packageName) &&
            (now - it.time) <= DEDUP_WINDOW_MS
        }
    }

    private fun markProcessed(amount: Int, channel: Channel, packageName: String) {
        recentProcessed.addLast(ProcessedEntry(amount, channel, packageName, System.currentTimeMillis()))
    }

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
            val cleanedText = cleanMarketingSuffix(item.fullText)
            val response = aiApi.parse(mapOf("input" to cleanedText))
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

            // H5: AI返回amount<=0无意义，直接丢弃
            if (aiItem.amount <= 0) {
                appLogger.debug("NLS", "AI返回amount<=0，丢弃")
                return
            }

            // M3: type无效时 fallback 为 expense（防下游非空字段崩溃）
            val safeType = if (aiItem.type in setOf("expense", "income", "transfer")) aiItem.type else "expense"

            val validation = aiResultValidator.validate(
                amount = aiItem.amount,
                type = aiItem.type,
                categoryId = aiItem.categoryId,
                description = aiItem.description,
            )

            val finalCategoryId = learnedCategoryId ?: aiItem.categoryId

            val isComplete = aiItem.amount > 0 &&
                safeType in setOf("expense", "income", "transfer") &&
                validation.isValid &&
                (learnedCategoryId != null || (
                    aiItem.categoryId != null &&
                    !aiItem.categoryName.isNullOrBlank() &&
                    aiItem.categoryName.lowercase() !in setOf("其他", "其它", "未分类", "other")
                ))

            // 构建候选项，统一进入评分窗口
            val candidate = ScoredCandidate(
                item = item,
                amount = aiItem.amount,
                type = safeType,
                categoryId = finalCategoryId,
                categoryName = aiItem.categoryName,
                categoryIcon = aiItem.categoryIcon,
                description = aiItem.description ?: aiItem.categoryName,
                source = if (learnedCategoryId != null) "learning+ai" else "ai",
                score = aiItemScore(aiItem),
                isComplete = isComplete,
                receivedAt = item.receivedAt,
            )
            tryCommit(candidate)
        } catch (e: Exception) {
            appLogger.error("NLS", "AI异常: ${e.message}")
            Timber.w(e, "AI 解析失败，丢弃: ${item.packageName}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 评分窗口 + 去重 + 入库（统一抽象）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 10s 评分窗口：同金额的 AI 结果进来后比较 score，保留最优。
     * 10s 到期后执行 commitBest()。
     * 10s-60s 之间再来的同金额直接被 isDuplicateAcrossChannels 拦住。
     */
    private fun tryCommit(candidate: ScoredCandidate) {
        val key = candidate.amount
        val existing = scoringPool[key]

        if (existing != null && (candidate.receivedAt - existing.receivedAt) <= SCORE_WINDOW_MS) {
            // 10s 内同金额：比较 score，保留更好的
            if (candidate.score > existing.score) {
                scoringPool[key] = candidate
                appLogger.debug("NLS", "评分替换: amount=$key newScore=${candidate.score} > oldScore=${existing.score}")
            } else {
                appLogger.debug("NLS", "评分丢弃: amount=$key score=${candidate.score} ≤ existing=${existing.score}")
            }
        } else {
            // 首条或超过 10s 的新交易
            scoringPool[key] = candidate
            scoringJobs[key]?.cancel()
            appLogger.debug("NLS", "评分窗口启动: amount=$key score=${candidate.score} isComplete=${candidate.isComplete} channel=${candidate.item.channel}")
            scoringJobs[key] = processorScope.launch {
                delay(SCORE_WINDOW_MS)
                val best = scoringPool.remove(key) ?: run {
                    appLogger.warn("NLS", "评分窗口到期但池中无数据: amount=$key")
                    return@launch
                }
                scoringJobs.remove(key)
                commitBest(best)
            }
        }
    }

    /**
     * 10s 到期，执行去重 + 入库/待审。统一路径。
     */
    private suspend fun commitBest(candidate: ScoredCandidate) {
        appLogger.debug("NLS", "commitBest: amount=${candidate.amount} score=${candidate.score} isComplete=${candidate.isComplete}")
        insertMutex.withLock {
            // 跨渠道去重（内存）
            if (isDuplicateAcrossChannels(candidate.amount, candidate.item.channel, candidate.item.packageName, candidate.receivedAt)) {
                appLogger.debug("NLS", "去重(内存): channel=${candidate.item.channel} amount=${candidate.amount}")
                return@withLock
            }
            // 跨渠道去重（DB 兜底）
            val dbDuplicate = notificationRecordDao.findRecentConfirmedFromOtherChannel(
                candidate.amount, candidate.receivedAt - DEDUP_WINDOW_MS, candidate.item.packageName
            )
            if (dbDuplicate != null) {
                appLogger.debug("NLS", "去重(DB): amount=${candidate.amount}")
                return@withLock
            }

            // 入库或待审
            if (candidate.isComplete) {
                directInsert(
                    item = candidate.item,
                    amount = candidate.amount,
                    type = candidate.type,
                    categoryId = candidate.categoryId,
                    categoryName = candidate.categoryName,
                    categoryIcon = candidate.categoryIcon,
                    description = candidate.description,
                    source = candidate.source,
                )
                appLogger.info("NLS", "✓入库: ¥${"%.2f".format(candidate.amount/100.0)} ${candidate.description} type=${candidate.type} score=${candidate.score}")
                // 触发学习
                val learnKey = candidate.description?.trim()
                if (!learnKey.isNullOrBlank() && candidate.categoryId != null) {
                    categoryLearningEngine.learnFromCorrection(learnKey, candidate.categoryId)
                }
            } else {
                appLogger.info("NLS", "→待审: amount=${candidate.amount} type=${candidate.type} cat=${candidate.categoryName} score=${candidate.score}")
                insertPendingReview(candidate.item, candidate.amount, candidate.type, candidate.categoryName, candidate.description)
            }
            markProcessed(candidate.amount, candidate.item.channel, candidate.item.packageName)
        }
    }

    /**
     * 清理通知文本中的营销后缀，避免 AI 误识别。
     * 支付宝"交易提醒"格式：真实交易信息 + "点击领取XX" 营销。
     * 截掉"点击"之后的所有内容。
     */
    private fun cleanMarketingSuffix(text: String): String {
        val cutoffs = listOf("点击领取", "点击查看", "点击开启", "戳我领", "立即领取")
        for (cutoff in cutoffs) {
            val idx = text.indexOf(cutoff)
            if (idx > 0) return text.substring(0, idx).trim()
        }
        return text
    }

    /**
     * AI 返回多条时选最优：按信息完整度评分。
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
        categoryName: String? = null,
        categoryIcon: String? = null,
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
            categoryName = categoryName,
            categoryIcon = categoryIcon,
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
        insertPendingReview(item, aiItem.amount, aiItem.type, aiItem.categoryName, aiItem.description ?: aiItem.categoryName)
    }

    private suspend fun insertPendingReview(
        item: Item,
        amount: Int,
        type: String,
        categoryName: String?,
        description: String?,
    ) {
        val recordId = notificationRecordDao.insert(
            NotificationRecordEntity(
                packageName = item.packageName,
                title = item.title.ifBlank { null },
                content = item.fullText,
                parsedAmount = amount.takeIf { it > 0 },
                parsedType = type.takeIf { it in setOf("expense", "income", "transfer") },
                parsedDescription = description ?: categoryName,
                status = "parsed",
                receivedAt = item.receivedAt,
            )
        )

        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showConfirmNotification(
            context = context,
            recordId = recordId,
            amount = amount.takeIf { it > 0 } ?: 0,
            description = description ?: categoryName,
            source = NotificationSourceMapping.friendlyName(item.packageName),
            privacyMode = privacyMode,
            type = type,
        )
    }
}