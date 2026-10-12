package com.aibill.android.data.record

import android.content.Context
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.record.RecordFailure
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.RecordOutcome
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.domain.usecase.StreakTracker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.WidgetDataUpdater
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationSourceMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 入账 module 的实现（原样搬迁 5 条手写写入路径）。
 *
 * 独占：clientId 生成、时钟、entity 装配、**送服务端确认**、跨表写入、分类预测/学习、
 * widget 累加、回执/轻通知、隐私档读取。调用方只表达意图（见 `RecordIntent`）。
 *
 * ## 顺序不变量：先服务端确认 → 再写本地 → 再发回执
 *
 * [confirmAndCache] 是唯一的落库出口：它先 `POST /transactions` 按 `client_id`
 * 拿服务端 id，拿到才把行（`sync_status='synced'` + `server_transaction_id`）与审计行
 * 写进 Room；拿不到就**什么都不写**，把 [RecordFailure] 原样带回去。
 * 因此「写进本地缓存」这件事本身就是「服务端已确认」的证据，
 * `pending_transactions` 里不会有第三种状态（见 `docs/ARCHITECTURE.md` §6.1）。
 *
 * ## 失败绝不静默，也绝不自己兜底
 *
 * 服务端没确认时，本类**不**写库、**不**发回执、**不**刷 widget/streak、**不**学分类，
 * 只把失败原因回传。把它变成用户看得见的反馈是调用方的事
 * （手工 → Snackbar、通知自动 → 降级待审池、确认 → Toast）。
 * 这样「已记录」类回执在结构上就不可能先于服务端确认发出去。
 */
@Singleton
class RecordIntakeImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordWriter: RecordWriter,
    private val transactionApi: TransactionApi,
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
    }

    // ═══════════════════════════════════════════════════════════════
    // AI 自动入账（原 NotificationProcessor.directInsert）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun intakeNotificationAuto(intent: RecordIntent.NotificationAuto): RecordOutcome {
        val clientId = newClientId()
        // ⚠️ 日期取**通知到达时刻**，不是解析/入账时刻：通知 23:40 到、服务端 00:05 才解析完时，
        // 这笔属于 23:40 那天。服务端已按 `received_at` 锚定「今天」，这里必须用同一个锚点，
        // 否则跨午夜迟发仍会记错一天（服务端复核 R-24）。401 重放同理 → 落回原始交易日期。
        val moment = recordClock.at(intent.captured.receivedAt)
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
            // AI 反馈闭环的锚点：只有 AI 自动入账路径会带（其余 intent 默认 null）
            parseLogId = intent.parseLogId,
        )

        // 送服务端 + 写缓存行 + 新建审计行 + 回填 linked_client_id，全在 confirmAndCache 里
        val cached = confirmAndCache(
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
        if (cached.failure != null) return RecordOutcome(clientId = clientId, failure = cached.failure)
        val recordId = cached.recordId

        streakTracker.onTransactionRecorded()
        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = intent.type.toTransactionType(),
            amountCents = intent.amount,
            date = pending.date,
        )
        if (recordId == null) {
            // 审计行是本意图**新建**的，写入器理应回传它的 id（Room 的 @Insert 自增主键）。
            // 拿不到就不发回执：recordId 同时是通知 id 与「撤销」的定位键，
            // 拿一个假 id（如历史的 orZero() → 0）去发通知 = 用另一条通知覆盖掉真实的那条，
            // 用户点「撤销」会撤销错账。宁可这一笔没有回执。
            Timber.e("AI 自动入账未拿到审计记录 id，跳过回执 clientId=%s", clientId)
        } else {
            NotificationHelper.showAutoRecordedNotification(
                context = context,
                recordId = recordId,
                amount = intent.amount,
                description = intent.description,
                source = sourceDetail,
                privacyMode = userPreferences.notificationPrivacy.first(),
                clientId = clientId,
            )
        }
        // 学习放在回执**之后**：原实现是在 commitBest 里 directInsert 返回后才学，
        // 这里保持同序（顺序本身不影响结果，但收口不改变既有行为）。
        val learnKey = intent.description?.trim()
        if (!learnKey.isNullOrBlank() && intent.categoryId != null) {
            categoryLearningEngine.learnFromCorrection(learnKey, intent.categoryId)
        }
        return RecordOutcome(clientId = clientId, recordId = recordId)
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
            // 只写分类 id，不写 name/icon：与手工记账写冗余 name/icon 的差异一样，保留。
            categoryId = predictedCategoryId,
            description = intent.description,
            date = moment.date,
            time = moment.time,
            source = SOURCE_APP_NOTIFICATION,
            sourceDetail = sourceDetail,
            clientCreatedAt = moment.instant.toString(),
        )

        val cached = confirmAndCache(pending = pending, confirmRecordId = intent.recordId)
        if (cached.failure != null) return RecordOutcome(clientId = clientId, failure = cached.failure)

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
        return RecordOutcome(clientId = clientId)
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

        val cached = confirmAndCache(pending = pending, confirmRecordId = intent.recordId)
        if (cached.failure != null) return RecordOutcome(clientId = clientId, failure = cached.failure)

        WidgetDataUpdater.notifyTransactionAdded(
            context = context,
            type = intent.type.toTransactionType(),
            amountCents = intent.amount,
            date = pending.date,
        )
        // 不预测分类、不学习：学习在 confirmWithEdit 里显式做（用户主动改了分类才学）
        return RecordOutcome(clientId = clientId)
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
            source = SOURCE_TASKER,
            sourceDetail = SOURCE_DETAIL_TASKER,
            // 历史口径：本入口的 client_created_at 是**无时区**的本地日期时间串，
            // 与其余路径的 Instant（UTC）不同。保留，不在本次收口里统一。
            clientCreatedAt = moment.localDateTime.toString(),
        )

        val cached = confirmAndCache(pending = pending)
        if (cached.failure != null) return RecordOutcome(clientId = clientId, failure = cached.failure)
        // 不刷 widget：原实现就没有刷，保留。
        return RecordOutcome(clientId = clientId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共
    // ═══════════════════════════════════════════════════════════════

    /**
     * 送服务端确认 → 确认了才写本地缓存与审计行。
     *
     * ## 成功判据
     *
     * `code == 0`，**且** `created[]` 或 `duplicates[]` 里按 `client_id` 找得到这条
     * （`TransactionDto.id` 非空，匹配到即等于拿回了 id）。
     *
     * `duplicates` 必须算确认：`RetryInterceptor` 对 IOException 会重发同一个 body，
     * 首次请求其实已落库、只是响应丢了，重发拿到的就是它——判成失败等于对用户
     * 谎报「没记上」，用户重记就是重复账。
     *
     * 两个列表都没匹配到自己才算没确认（服务端漏字段 / 转换异常时的僵尸行）。
     *
     * ## 为什么「写缓存」和「写审计行」必须同生共死
     *
     * 两者都在这里、都在服务端确认之后：失败时审计行保持 `parsed`（用户还在通知中心
     * 看得见、能手动确认），缓存行根本不存在。拆开写就会出现「账没记上、通知却说已确认」。
     *
     * @param pending 待确认的那一笔（`sync_status` 还是默认值，确认后才被改写）
     * @return 新建的审计记录 id（[newRecord] 非 null 时）与失败原因，二者互斥
     */
    private suspend fun confirmAndCache(
        pending: PendingTransactionEntity,
        newRecord: NotificationRecordEntity? = null,
        confirmRecordId: Long? = null,
    ): CacheResult {
        val serverId = when (val confirmation = submit(pending)) {
            is Submit.Confirmed -> confirmation.serverId
            is Submit.Failed -> return CacheResult(recordId = null, failure = confirmation.failure)
        }

        val recordId = recordWriter.write(
            RecordWrite(
                pending = pending.copy(
                    syncStatus = SYNC_STATUS_SYNCED,
                    serverTransactionId = serverId,
                ),
                newRecord = newRecord,
                confirmRecordId = confirmRecordId,
            ),
        )
        return CacheResult(recordId = recordId, failure = null)
    }

    /**
     * `POST /transactions` 单条，按 `client_id` 回认服务端 id。
     *
     * ## 成功判据：`created[]` **或** `duplicates[]` 里按 `client_id` 匹配到 id
     *
     * `duplicates` 必须算确认，否则会同时犯两个错：`RetryInterceptor` 对 IOException 会
     * **重发同一个 body**，首次请求其实已落库、只是响应丢了，重发拿到的是 `duplicates[]` ——
     * 判成失败就会「服务端已有这笔，却告诉用户没记上」，而用户重试（手工每次生成新
     * `clientId`）就是**重复账**。
     *
     * 只有「两个列表都没匹配到自己」才算失败：那说明服务端漏字段 / 转换异常，
     * 当成记上了反而会在本地多一行服务端没有的账。
     */
    private suspend fun submit(entity: PendingTransactionEntity): Submit = try {
        val response = transactionApi.createTransactions(
            CreateTransactionRequest(items = listOf(entity.toItemRequest())),
        )
        val data = response.data
        val matched = (data?.created?.firstOrNull { it.clientId == entity.clientId }
            ?: data?.duplicates?.firstOrNull { it.clientId == entity.clientId })?.id
        when {
            response.code != 0 ->
                Submit.Failed(RecordFailure.Rejected(response.code, response.message))
            matched == null ->
                Submit.Failed(
                    RecordFailure.Rejected(
                        code = response.code,
                        reason = "服务端未确认这笔记录",
                    ),
                )
            else -> Submit.Confirmed(matched)
        }
    } catch (e: HttpException) {
        Submit.Failed(RecordFailure.Rejected(e.code(), "服务器错误: ${e.code()}"))
    } catch (e: IOException) {
        Submit.Failed(RecordFailure.Offline)
    } catch (e: CancellationException) {
        // 挂起调用被取消（协程作用域结束）必须正常传播，不能当成「记账失败」吞掉，
        // 否则破坏结构化并发。
        throw e
    } catch (e: Exception) {
        // 兜住其余一切：接收器跑在 ApplicationScope 上，本工程没有全局
        // CoroutineExceptionHandler，异常逃出去 = 直接崩进程。
        Submit.Failed(RecordFailure.Rejected(Result.ERROR_UNKNOWN, "未知错误: ${e.message}"))
    }

    private fun PendingTransactionEntity.toItemRequest() = TransactionItemRequest(
        clientId = clientId,
        clientType = "app_android",
        source = source,
        sourceDetail = sourceDetail,
        type = type,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        targetAccountId = targetAccountId,
        description = description,
        date = date,
        time = time,
        tags = tags?.split(",")?.filter { it.isNotBlank() },
        clientCreatedAt = clientCreatedAt,
        aiRawInput = aiRawInput,
    )

    /** 服务端确认的结果。 */
    private sealed interface Submit {
        data class Confirmed(val serverId: Int) : Submit
        data class Failed(val failure: RecordFailure) : Submit
    }

    /** [confirmAndCache] 的结果：写了（带新建审计行 id）或没写（带失败原因）。 */
    private data class CacheResult(val recordId: Long?, val failure: RecordFailure?)

    /** clientId = UUID v4（幂等与撤销的唯一凭据，架构决策 #6） */
    private fun newClientId(): String = UUID.randomUUID().toString()

    private fun String.toTransactionType(): TransactionType =
        TransactionType.fromValue(this) ?: TransactionType.EXPENSE

    private companion object {
        const val SOURCE_APP_NOTIFICATION = "app_notification"

        /**
         * 外部 Intent（Tasker / 快捷指令）路径的 `source`。
         *
         * ⚠️ 取值必须是服务端 `POST /api/transactions` 允许的枚举（`server/src/routes/transaction.ts`
         * 的 zod enum，DB 还有 `transactions.source` 的 CHECK 约束）。历史值 `"external_intent"`
         * 不在枚举内 → 该入口恒定 400；只放宽 zod 也会被 CHECK 挡成 500（服务端已确认不做重建表迁移），
         * 所以这里改用枚举内的 `"app_notification"`。
         *
         * **语义真相由 [SOURCE_DETAIL_TASKER]（`tasker_quick_record`）承载**——它才是区分
         * 「Tasker 静默入账」与「通知自动记账」的依据，取值不变。
         */
        /**
         * 外部 Intent（Tasker / 快捷指令）**静默入账**。
         *
         * ⚠️ 值与通知自动记账相同（`app_notification`），**不是** `external_intent`：
         * 服务端 `transactions.source` 有 DB CHECK 约束只允许枚举内的值（送 `external_intent` 会 400）。
         * 区分职责由 [SOURCE_DETAIL_TASKER]（`tasker_quick_record`）承担，用户可见来源名仍是 Tasker。
         */
        const val SOURCE_TASKER = "app_notification"

        /** Tasker 与通知自动记账的区分依据，丢了服务端两类记录就分不出来 */
        const val SOURCE_DETAIL_TASKER = "tasker_quick_record"

        const val STATUS_CONFIRMED = "confirmed"

        /** 已确认缓存行的唯一取值：非 `synced` 的行都是历史遗留，不参与任何判定 */
        const val SYNC_STATUS_SYNCED = "synced"

        /** 确认回执的通知 id 偏移：避开原确认弹窗的通知 id */
        const val CONFIRM_RECEIPT_ID_OFFSET = 100_000L
    }
}
