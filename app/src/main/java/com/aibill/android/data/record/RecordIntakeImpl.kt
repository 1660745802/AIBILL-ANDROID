package com.aibill.android.data.record

import android.content.Context
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.RecordOutcome
import com.aibill.android.domain.record.SyncScheduling
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.domain.usecase.StreakTracker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.SyncScheduler
import com.aibill.android.service.WidgetDataUpdater
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationSourceMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 入账 module 的实现（原样搬迁 6 条手写写入路径）。
 *
 * 独占：clientId 生成、时钟、entity 装配、跨表写入、分类预测/学习、排同步调度、
 * widget 累加、回执/轻通知、隐私档读取。调用方只表达意图（见 `RecordIntent`）。
 *
 * ## 顺序不变量：先写库、后排调度
 *
 * 反过来可能让 SyncWorker 早于本行提交就跑到空队列（白跑一轮，还要等下一次网络跳变才补传）。
 * 全部 6 条路径都遵守这一条。
 *
 * ## ⚠️ 排同步失败只记日志、绝不回滚、绝不抛出
 *
 * 入队失败（WorkManager 抛异常）时这笔账**已经落库**，回滚等于丢用户数据；
 * 抛给调用方则会让异常逃出调用方的协程作用域——本工程**没有**全局
 * `CoroutineExceptionHandler`，未捕获异常走线程默认处理器 = **直接崩溃进程**；
 * 即便假设它被吞，`ManualRecordViewModel` 末尾的 `isSaving` 复位也会被异常跳过，
 * 按钮永久卡在「保存中」。剩余兜底仍有：网络跳变、启动排同步、Home 横幅的手动同步按钮。
 *
 * 这条策略原先只写在 `TransactionRepositoryImpl.createTransactionOffline` 上，
 * 收口后由本类统一承担（弹窗/通知中心那几条原本连 try/catch 都没有，是行为变更，
 * 已在提交信息里写明）。
 *
 * ## Success 只代表「已入队」，不代表「已上传」
 *
 * [SyncScheduler.scheduleSyncIfNeeded] 只是把 SyncWorker 入队，真实上传结果由队列状态与
 * 横幅呈现。不得据此在调用方改写成「已同步 / 已上传」类文案。
 */
@Singleton
class RecordIntakeImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordWriter: RecordWriter,
    private val recordClock: RecordClock,
    private val userPreferences: UserPreferences,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val streakTracker: StreakTracker,
    private val rulesManager: NotificationRulesManager,
) : RecordIntake {

    override suspend fun intake(intent: RecordIntent): RecordOutcome = when (intent) {
        is RecordIntent.NotificationAuto -> intakeNotificationAuto(intent)
        is RecordIntent.NotificationConfirm -> intakeNotificationConfirm(intent)
        is RecordIntent.NotificationCenterConfirm -> intakeNotificationCenterConfirm(intent)
        is RecordIntent.ExternalQuickRecord -> intakeExternalQuickRecord(intent)
        is RecordIntent.ManualOffline -> intakeManualOffline(intent)
    }

    override fun scheduleBatchSync() {
        try {
            SyncScheduler.scheduleSyncIfNeeded(context)
        } catch (e: Exception) {
            Timber.w(e, "批量入账完成但排同步失败")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AI 自动入账（原 NotificationProcessor.directInsert）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeNotificationAuto(intent: RecordIntent.NotificationAuto): RecordOutcome {
        val clientId = newClientId()
        val moment = recordClock.now()
        // AI 自动入账读**云控**来源名映射（其余路径读硬编码表）——历史差异，保留。
        val sourceDetail = NotificationSourceMapping.friendlyName(intent.captured.packageName, rulesManager)

        val pending = PendingTransactionEntity(
            clientId = clientId,
            amount = intent.amount,
            categoryId = intent.categoryId,
            categoryName = intent.categoryName,
            categoryIcon = intent.categoryIcon,
            description = intent.description,
            date = moment.date,
            time = moment.time,
            type = intent.type,
            source = SOURCE_APP_NOTIFICATION,
            sourceDetail = sourceDetail,
            clientCreatedAt = moment.instant.toString(),
        )

        // 新建审计行 + 写队列行 + 回填 linked_client_id，三步在同一事务里
        val recordId = recordWriter.write(
            RecordWrite(
                pending = pending,
                newRecord = NotificationRecordEntity(
                    packageName = intent.captured.packageName,
                    title = intent.captured.title.ifBlank { null },
                    content = intent.captured.content,
                    parsedAmount = intent.amount,
                    parsedType = intent.type,
                    parsedDescription = intent.description,
                    status = STATUS_CONFIRMED,
                    receivedAt = intent.captured.receivedAt,
                ),
            )
        ).orZero()

        scheduleSync(clientId)
        streakTracker.onTransactionRecorded()
        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = intent.type.toTransactionType(),
            amountCents = intent.amount,
            date = pending.date,
        )
        NotificationHelper.showAutoRecordedNotification(
            context = context,
            recordId = recordId,
            amount = intent.amount,
            description = intent.description,
            source = sourceDetail,
            privacyMode = userPreferences.notificationPrivacy.first(),
            clientId = clientId,
        )
        // 学习放在回执**之后**：原实现是在 commitBest 里 directInsert 返回后才学，
        // 这里保持同序（顺序本身不影响结果，但收口不改变既有行为）。
        val learnKey = intent.description?.trim()
        if (!learnKey.isNullOrBlank() && intent.categoryId != null) {
            categoryLearningEngine.learnFromCorrection(learnKey, intent.categoryId)
        }
        return RecordOutcome(clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 弹窗确认 / 弹窗带备注确认（原 NotificationActionReceiver 两条路径）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeNotificationConfirm(intent: RecordIntent.NotificationConfirm): RecordOutcome {
        val clientId = newClientId()
        val moment = recordClock.now()
        // 弹窗确认读**硬编码**来源名映射（不带云控）——与 AI 自动路径的历史差异，保留。
        val sourceDetail = NotificationSourceMapping.friendlyName(intent.packageName)

        // 自动填分类：用描述作为关键词尝试匹配学习过的分类
        val predictedCategoryId = if (!intent.description.isNullOrBlank()) {
            categoryLearningEngine.matchCategory(intent.description)
        } else {
            null
        }

        val pending = PendingTransactionEntity(
            clientId = clientId,
            type = intent.type,
            amount = intent.amount,
            // 只写分类 id，不写 name/icon：与「手工落离线」写冗余 name/icon 的差异一样，保留。
            categoryId = predictedCategoryId,
            description = intent.description,
            date = moment.date,
            time = moment.time,
            source = SOURCE_APP_NOTIFICATION,
            sourceDetail = sourceDetail,
            clientCreatedAt = moment.instant.toString(),
        )

        recordWriter.write(RecordWrite(pending = pending, confirmRecordId = intent.recordId))

        scheduleSync(clientId)

        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = intent.type.toTransactionType(),
            amountCents = intent.amount,
            date = pending.date,
        )

        // 学习放在刷 widget 之后、回执之前，与原 handleConfirmWithNote 同序。
        // learn=false 是弹窗直接确认的历史行为（handleConfirm），保留不学习。
        if (intent.learn && predictedCategoryId != null && !intent.description.isNullOrBlank()) {
            categoryLearningEngine.learnFromCorrection(intent.description.trim(), predictedCategoryId)
        }

        // 轻量反馈通知（隐私档写死 false，与原实现一致）：见意图 KDoc
        NotificationHelper.showAutoRecordedNotification(
            context = context,
            // 使用不同 id 避免与原确认通知冲突
            recordId = intent.recordId + CONFIRM_RECEIPT_ID_OFFSET,
            amount = intent.amount,
            description = intent.description,
            source = sourceDetail,
            privacyMode = false,
        )
        return RecordOutcome(clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 通知中心单条 / 批量确认（原 NotificationCenterViewModel 两条路径）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeNotificationCenterConfirm(
        intent: RecordIntent.NotificationCenterConfirm,
    ): RecordOutcome {
        val clientId = newClientId()
        val moment = recordClock.now()
        val sourceDetail = NotificationSourceMapping.friendlyName(intent.packageName)

        val pending = PendingTransactionEntity(
            clientId = clientId,
            type = intent.type,
            amount = intent.amount,
            categoryId = intent.categoryId,
            categoryName = intent.categoryName,
            categoryIcon = intent.categoryIcon,
            description = intent.description,
            date = moment.date,
            time = moment.time,
            tags = if (intent.tags.isNotEmpty()) intent.tags.joinToString(",") else null,
            source = SOURCE_APP_NOTIFICATION,
            sourceDetail = sourceDetail,
            clientCreatedAt = moment.instant.toString(),
        )

        recordWriter.write(RecordWrite(pending = pending, confirmRecordId = intent.recordId))

        // 批量确认走 DEFERRED：由调用方在循环结束后统一排一次（历史行为，见 scheduleBatchSync）
        if (intent.scheduling == SyncScheduling.IMMEDIATE) {
            scheduleSync(clientId)
        }

        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = intent.type.toTransactionType(),
            amountCents = intent.amount,
            date = pending.date,
        )
        // 不预测分类、不学习：学习在 confirmWithEdit 里显式做（用户主动改了分类才学）
        return RecordOutcome(clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 外部 Intent 静默入账（原 ExternalActionReceiver.handleAutoConfirmRecord）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeExternalQuickRecord(intent: RecordIntent.ExternalQuickRecord): RecordOutcome {
        val clientId = newClientId()
        val moment = recordClock.now()

        val pending = PendingTransactionEntity(
            clientId = clientId,
            type = intent.type,
            amount = intent.amount,
            description = intent.description,
            date = moment.date,
            time = moment.time,
            source = SOURCE_EXTERNAL_INTENT,
            sourceDetail = SOURCE_DETAIL_TASKER,
            // 历史口径：本入口的 client_created_at 是**无时区**的本地日期时间串，
            // 与其余路径的 Instant（UTC）不同。保留，不在本次收口里统一。
            clientCreatedAt = moment.localDateTime.toString(),
        )

        recordWriter.write(RecordWrite(pending = pending))
        // 排一次同步：否则这条 pending 只能等「网络跳变 / 下次启动 / 用户手动点同步」——
        // WiFi 常连的静默入账会一直躺着。
        scheduleSync(clientId)
        // 不刷 widget：原实现就没有刷，保留。
        return RecordOutcome(clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 手工记账落离线（原 TransactionRepositoryImpl.createTransactionOffline）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeManualOffline(intent: RecordIntent.ManualOffline): RecordOutcome {
        val t = intent.transaction
        val clientId = t.clientId
        val pending = PendingTransactionEntity(
            clientId = clientId,
            type = t.type.value,
            amount = t.amount,
            categoryId = t.categoryId,
            // PR #43：冗余存 name/icon，未同步期间列表展示不需 join 分类表
            categoryName = t.categoryName,
            categoryIcon = t.categoryIcon,
            accountId = t.accountId,
            accountName = t.accountName,
            targetAccountId = t.targetAccountId,
            description = t.description,
            // 手工记账用**用户当时选的时间**，不是入账时刻
            date = t.date,
            time = t.time,
            tags = t.tags?.joinToString(","),
            source = t.source.value,
            clientCreatedAt = recordClock.now().instant.toString(),
        )
        recordWriter.write(RecordWrite(pending = pending))
        scheduleSync(clientId)
        // 不刷 widget：原实现就没有刷，保留。
        return RecordOutcome(clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共
    // ═══════════════════════════════════════════════════════════════

    /** clientId = UUID v4（幂等与撤销的唯一凭据，架构决策 #6） */
    private fun newClientId(): String = UUID.randomUUID().toString()

    /** 写库之后才排调度；失败只记日志（理由见类 KDoc） */
    private fun scheduleSync(clientId: String) {
        try {
            SyncScheduler.scheduleSyncIfNeeded(context)
        } catch (e: Exception) {
            Timber.w(e, "入账成功但排同步失败 clientId=%s", clientId)
        }
    }

    private fun String.toTransactionType(): TransactionType =
        TransactionType.fromValue(this) ?: TransactionType.EXPENSE

    private fun Long?.orZero(): Long = this ?: 0L

    private companion object {
        const val SOURCE_APP_NOTIFICATION = "app_notification"
        const val SOURCE_EXTERNAL_INTENT = "external_intent"

        /** Tasker 与通知自动记账的区分依据，丢了服务端两类记录就分不出来 */
        const val SOURCE_DETAIL_TASKER = "tasker_quick_record"

        const val STATUS_CONFIRMED = "confirmed"

        /** 确认回执的通知 id 偏移：避开原确认弹窗的通知 id */
        const val CONFIRM_RECEIPT_ID_OFFSET = 100_000L
    }
}
