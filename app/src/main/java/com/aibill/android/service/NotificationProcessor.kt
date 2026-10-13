package com.aibill.android.service

import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.NotificationSink
import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureArchive
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.NotificationInterpreter
import com.aibill.android.domain.notification.ParseOutcome
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.notification.ScoredAdmission
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.record.CapturedNotification
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationSourceMapping
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知入账的**协调者**（v5）。
 *
 * 三渠道统一入口（NLS / A11Y / SMS）。预筛放行后，本类只做接线，不持有任何判据或状态：
 *
 * ```
 * process(capture)
 *   → NotificationInterpreter.read         解析 + 校验，产出候选 / 明确失败原因
 *       ├ Parsed        → ScoredAdmission.submit  窗口择优 + 跨渠道判重
 *       │                    └ 产出 → commit      信息完整 → RecordIntake；否则 → 待审池
 *       ├ Unparsed      → 丢弃（不落待审 / 不发通知；计数 + warn）
 *       ├ InvalidInput  → 丢弃（入参非法 HTTP 400，单独计数，不混入 AI 失败率）
 *       ├ AuthExpired   → CaptureArchive.captureAuthPending 落 auth_pending，重登后重放
 *       └ NotATransaction / Disabled → 丢弃
 * ```
 *
 * 每条策略都是独立 module（interface 在 `domain/notification/`、实现在 `data/notification/`），
 * 各自有状态与时序、各自可单测：
 * [com.aibill.android.domain.notification.NotificationInterpreter]（解析与校验）、
 * [com.aibill.android.domain.notification.ScoredAdmission]（10s 窗口 + 60s 跨渠道判重）、
 * [com.aibill.android.domain.notification.CaptureArchive]（原文留痕与 401 重放）、
 * [com.aibill.android.domain.notification.AiCircuitBreaker]（AI 熔断，由预筛查询）。
 *
 * 本类保留的只有**路由**：谁产出了、下一步交给谁，以及「待审池写入 + 确认通知」
 * 这个最终出口（它同时要读隐私设置与来源名映射，属于装配而非策略）。
 */
@Singleton
class NotificationProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val interpreter: NotificationInterpreter,
    private val scoredAdmission: ScoredAdmission,
    private val captureArchive: CaptureArchive,
    private val notificationRecordDao: NotificationRecordDao,
    private val recordIntake: RecordIntake,
    private val userPreferences: UserPreferences,
    private val appLogger: AppLogger,
    private val ruleSource: NotificationRuleSource,
    private val captureFunnelStats: CaptureFunnelStats,
) : NotificationSink {

    /**
     * 处理一条预筛放行的通知。
     *
     * suspend 不等窗口：评分窗口内部异步产出，本方法只登记后返回。
     */
    override suspend fun process(capture: Capture) {
        when (val outcome = interpreter.read(capture)) {
            is ParseOutcome.Disabled, is ParseOutcome.NotATransaction -> Unit
            // 解析失败 = **不确定**（区别于「AI 判定不是账」）：既不是「是账」也不是「不是账」。
            // 不落待审、不发通知、不打扰用户 —— 让用户面对一条没有金额/分类的原文
            // 等于「形式上留痕、实质上丢弃」（用户没法审）。留痕责任在服务端 `ai_parse_logs`。
            // ⚠️ 不要在这里用正则判「像不像账」：正则只做筛选，「是不是账」由 AI 判定。
            // 本地只做两件事：计数（失败率可见，进 ⑤ 上报与导出日志）+ warn（排障用）。
            // 401 是另一回事（会话过期）→ 见下面的 AuthExpired 分支（待补偿重放）。
            is ParseOutcome.Unparsed -> {
                captureFunnelStats.record(CaptureFunnelStats.unparsedFailedStage(capture.channel))
                appLogger.warn(
                    "NLS",
                    "解析失败，丢弃（不留待审）: reason=${outcome.reason} " +
                        "pkg=${capture.packageName} text=${capture.fullText.take(30)}"
                )
            }
            is ParseOutcome.AuthExpired -> captureArchive.captureAuthPending(capture)
            // 入参被服务端拒（空 / 超长）= **客户端请求问题**，不是「AI 挂了」：
            // 单独成桶（client_only.unparsed_input_invalid），不混入 AI 失败率（服务端 `docs/API.md` 附录 C §10）。
            is ParseOutcome.InvalidInput -> {
                captureFunnelStats.record(CaptureFunnelStats.unparsedInputInvalidStage(capture.channel))
                appLogger.warn(
                    "NLS",
                    "AI 入参被拒，丢弃（不留待审）: reason=${outcome.reason} pkg=${capture.packageName}"
                )
            }
            is ParseOutcome.Parsed -> scoredAdmission.submit(outcome.transaction) { commit(it) }
        }
    }

    /**
     * 重放待补偿队列。
     *
     * 由 [com.aibill.android.data.repository.AuthRepositoryImpl] 在
     * [com.aibill.android.domain.ownership.DisposalOutcome.Retained]（同账号重登、
     * 本地数据一张未动）之后调用；换号/首次登录拿到的是 `Cleared`，队列已被一起清掉
     * ——那是另一个用户的数据，本来就不该重放。
     *
     * 逐条重新走 [process]，解析成功会正常入库并转 confirmed/parsed；解析失败不再留痕。
     * 终态标记与「不再无限重试」由 [CaptureArchive.replay] 处理。
     */
    suspend fun replayAuthPending() {
        captureArchive.replay { pending ->
            process(
                Capture(
                    channel = SourceChannel.NLS,
                    packageName = pending.packageName,
                    title = pending.title,
                    fullText = pending.content,
                    receivedAt = pending.receivedAt,
                )
            )
        }
    }

    /**
     * 窗口产出 → 入库或待审。
     *
     * 信息完整 → 送服务端确认（[RecordIntake]）；**信息完整但服务端没确认**（没网 /
     * 业务错误 / 鉴权）与信息不完整走同一条降级路：通知原文进待审池，信息不丢。
     */
    private suspend fun commit(candidate: ParsedTransaction) {
        if (candidate.isComplete && directInsert(candidate)) {
            appLogger.info(
                "NLS",
                "✓入库: ¥${"%.2f".format(candidate.amount / 100.0)} ${candidate.description} " +
                    "type=${candidate.type} score=${candidate.score} channel=${candidate.capture.channel}"
            )
            // 分类学习已下沉到入账 module（RecordIntent.NotificationAuto 的学习步骤）：
            // 学习必须在「服务端确认之后」才发生，而成败只有 module 内部知道。
        } else {
            if (candidate.isComplete) {
                appLogger.warn("NLS", "→服务端未确认，降级待审: amount=${candidate.amount} score=${candidate.score}")
            } else {
                appLogger.info(
                    "NLS",
                    "→待审: amount=${candidate.amount} type=${candidate.type} " +
                        "cat=${candidate.categoryName} score=${candidate.score}"
                )
            }
            insertPendingReview(candidate)
        }
    }

    /**
     * 信息完整，直接入库 + 发轻通知。
     *
     * 本函数只剩「把候选翻译成入账意图」：clientId 生成、entity 装配、送服务端确认、
     * 跨表写入、刷 streak / widget、隐私档与回执、分类学习全部由 [RecordIntake] 承担。
     * 想看入账行为请看 `data/record/RecordIntakeImpl`。
     *
     * @return `true` = 服务端确认、本地已落；`false` = 没记上，调用方必须降级进待审池
     */
    private suspend fun directInsert(candidate: ParsedTransaction): Boolean {
        val outcome = recordIntake.intake(
            RecordIntent.NotificationAuto(
                captured = CapturedNotification(
                    packageName = candidate.capture.packageName,
                    title = candidate.capture.title,
                    content = candidate.capture.fullText,
                    receivedAt = candidate.capture.receivedAt,
                ),
                amount = candidate.amount,
                type = candidate.type,
                categoryId = candidate.categoryId,
                categoryName = candidate.categoryName,
                categoryIcon = candidate.categoryIcon,
                description = candidate.description,
                parseLogId = candidate.parseLogId,
            )
        )
        return outcome.recorded
    }

    /** AI 有结果但信息不完整（或服务端未确认）→ 进待审池 + 发确认通知 */
    private suspend fun insertPendingReview(candidate: ParsedTransaction) {
        val recordId = notificationRecordDao.insert(
            NotificationRecordEntity(
                packageName = candidate.capture.packageName,
                title = candidate.capture.title.ifBlank { null },
                content = candidate.capture.fullText,
                parsedAmount = candidate.amount.takeIf { it > 0 },
                parsedType = candidate.type.takeIf { it in setOf("expense", "income", "transfer") },
                parsedDescription = candidate.description ?: candidate.categoryName,
                status = STATUS_PARSED,
                receivedAt = candidate.capture.receivedAt,
            )
        )

        val privacyMode = userPreferences.notificationPrivacy.first()
        NotificationHelper.showConfirmNotification(
            context = context,
            recordId = recordId,
            amount = candidate.amount.takeIf { it > 0 } ?: 0,
            description = candidate.description ?: candidate.categoryName,
            source = NotificationSourceMapping.friendlyName(candidate.capture.packageName, ruleSource.snapshot().sourceMapping),
            privacyMode = privacyMode,
        )
    }

    private companion object {
        /** AI 有结果、待用户确认（通知中心列表读 `status IN ('raw','parsed')`）。 */
        const val STATUS_PARSED = "parsed"
    }
}
