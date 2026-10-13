package com.aibill.android.data.notification

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.request.ParseSourceDto
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.BalanceConsistency
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.NotificationInterpreter
import com.aibill.android.domain.notification.ParseOutcome
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.data.notification.rules.NotificationRulesManager
import com.aibill.android.util.AiResultValidator
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [NotificationInterpreter] 的实现：调 AI → 二次校验 → 装配候选。
 *
 * 判定顺序与时间常数都是行为，逐字保留自拆出前的 `NotificationProcessor.process()`：
 *
 * 1. 总闸（设置里关掉就不处理，用户明确选择）；
 * 2. 分类学习命中（学习规则的 key 是「商家/描述」，必须在**完整通知文本**里做
 *    contains 匹配；只用 title 会命中不了，见下）；
 * 3. 调 AI（先截营销后缀）；
 * 4. 业务失败 → 记熔断失败 + [ParseOutcome.Unparsed]；
 * 5. 空 items / amount ≤ 0 → 判定非账；
 * 6. 校验 + 装配候选（type 非法回落 expense、电商描述脱敏、跨渠道比较分）。
 *
 * 一次 AI 调用只回一笔：服务端**全局保证 `items` ≤ 1**（已按单一标准挑好），客户端
 * 只取 `items.first()`（本地不再挑选，避免第二套口径）。多余条数见响应
 * `extra_items_dropped`；旧服务端仍回多条时确定性取第一笔 + warn + 计数，绝不静默丢。
 *
 * 熔断的**失败计数在这里上报**，但「要不要查询熔断」在预筛 module：这条边界保证了
 * 熔断状态只有一个写入口（真正调 AI 的地方），而放行策略只有一处（预筛）。
 */
@Singleton
class NotificationInterpreterImpl @Inject constructor(
    private val aiApi: AiApi,
    private val aiResultValidator: AiResultValidator,
    private val circuitBreaker: AiCircuitBreaker,
    private val userPreferences: UserPreferences,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val appLogger: AppLogger,
    private val rulesManager: NotificationRulesManager,
    private val balanceConsistency: BalanceConsistency,
    private val captureFunnelStats: CaptureFunnelStats,
) : NotificationInterpreter {

    override suspend fun read(capture: Capture): ParseOutcome {
        if (!userPreferences.aiParseEnabled.first()) {
            appLogger.info("NLS", "AI关闭，丢弃: ${capture.fullText.take(30)}")
            return ParseOutcome.Disabled
        }

        // 学习规则的 key 是「商家/描述」（learnFromCorrection 全部以 description 学入），
        // 匹配必须在完整通知文本里做 contains 词边界匹配。
        // 之前只用 title（常为"微信支付"等泛化词）匹配，学了也命中不了，
        // 智能分类学习在通知链路形同虚设（PR review 修复）。
        val learnedCategoryId = categoryLearningEngine.matchCategory(capture.fullText)

        return try {
            appLogger.info("NLS", "调AI: pkg=${capture.packageName} text=${capture.fullText.take(30)}")
            val cleanedText = cleanMarketingSuffix(capture.fullText)
            // 带上上下文（§2）：channel/source 供服务端归因，received_at 让「今天」按
            // **通知到达时刻**算（跨午夜迟发不会记到明天），title/body 供后续判定用。
            // 全部可选，旧服务端忽略之。
            val response = aiApi.parse(
                AiParseRequest(
                    input = cleanedText,
                    channel = capture.channel.logTag.lowercase(),
                    source = ParseSourceDto(packageName = capture.packageName),
                    title = capture.title.takeIf(String::isNotBlank),
                    // body 用**未截营销后缀**的原文：金额佐证看到的应是最全的文本
                    body = capture.fullText.takeIf(String::isNotBlank),
                    receivedAt = formatWithOffset(capture.receivedAt),
                )
            )
            if (response.code != 0 || response.data == null) {
                when (classifyAiFailure(response)) {
                    AiFailure.NOT_A_BILL -> {
                        // AI 明确回答「这不是账」= 一次**成功**的 AI 调用，不是故障：
                        // 不计失败（不污染 client_only.unparsed_ai_failed），也不记熔断失败——
                        // 否则「AI 正常但判定非账」的界面会把包名拉黑（真交易被静音）。
                        circuitBreaker.recordOutcome(capture.packageName, success = true)
                        appLogger.info(
                            "NLS",
                            "AI判定非账(not_a_bill): code=${response.code} " +
                                "msg=${response.message} pkg=${capture.packageName}"
                        )
                        return ParseOutcome.NotATransaction
                    }
                    AiFailure.UNAVAILABLE, null -> {
                        circuitBreaker.recordOutcome(capture.packageName, success = false)
                        // 必须带 message：5001 既可能是模型拒答也可能是超时/超长，
                        // 只打 code 时排障完全无从下手（ApiResponse.message 一直有值）。
                        appLogger.warn(
                            "NLS",
                            "AI不可用(unavailable): code=${response.code} " +
                                "msg=${response.message} pkg=${capture.packageName}"
                        )
                        return ParseOutcome.Unparsed("code=${response.code}")
                    }
                }
            }
            circuitBreaker.recordOutcome(capture.packageName, success = true)
            // 边界：服务端**全局保证 `items` ≤ 1**（挑选标准在服务端 `lib/main-item.ts`）：
            // 0 笔 = 走手动兜底（现有的 [ParseOutcome.NotATransaction] 路径）；1 笔 = 正常。
            // 本地**不再做任何挑选**——服务端已挑好，本地再挑就是第二套口径。
            val items = response.data.items
            if (items.isEmpty()) {
                appLogger.info("NLS", "AI判定非支付，丢弃")
                return ParseOutcome.NotATransaction
            }

            // 多余条目：服务端已挑好一笔 → 只取第一笔；旧服务端可能仍回多条，
            // 这里只兜底计数，不再本地挑选。
            recordExtraItemsIfAny(
                capture = capture,
                itemCount = items.size,
                extraItemsDropped = response.data.extraItemsDropped,
            )

            val aiItem = items.first()

            // H5: AI返回amount<=0无意义，直接丢弃
            if (aiItem.amount <= 0) {
                appLogger.debug("NLS", "AI返回amount<=0，丢弃")
                return ParseOutcome.NotATransaction
            }

            // M3: type无效时 fallback 为 expense（防下游非空字段崩溃）
            val safeType = if (aiItem.type in VALID_TYPES) aiItem.type else "expense"

            val validation = aiResultValidator.validate(
                amount = aiItem.amount,
                type = aiItem.type,
                categoryId = aiItem.categoryId,
                description = aiItem.description,
                // 必传：校验 5 靠它拦「金额从群名误取」的幻觉（A1）。
                // 用清洗后的 cleanedText：与实际送 AI 的文本逐字一致，
                // 金额佐证看到的才和模型看到的是同一份。
                sourceText = cleanedText,
            )

            val finalCategoryId = learnedCategoryId ?: aiItem.categoryId

            // 「该不该自动入账」的阈值**只在服务端一处实现**（`needs_review`），
            // 客户端不再自设第二套阈值 —— 旧服务端没这个字段时，才回退到下面这套
            // 客户端启发式（分类缺失/泛化即算信息不完整），保证行为不变。
            val clientHeuristicIncomplete = learnedCategoryId == null && (
                aiItem.categoryId == null ||
                    aiItem.categoryName.isNullOrBlank() ||
                    aiItem.categoryName.lowercase() in GENERIC_CATEGORY_NAMES
                )
            val needsReview = aiItem.needsReview ?: clientHeuristicIncomplete

            // 余额自洽校验（纯本地）：用「上一条同卡余额 ± 本笔金额 ≈ 本条余额」自证金额没抽错。
            // 只在「有把握的矛盾」上说话：不一致 → 该笔不自动入账（进待审池），**不拒收**。
            // 不影响 needs_review 的判定（那是服务端的阈值），两者取「与」。
            val balanceVerdict = balanceConsistency.checkAndRecord(
                cardTail = aiItem.cardTail,
                balanceText = aiItem.balanceText,
                balanceAfterCents = aiItem.balanceAfterCents,
                amountCents = aiItem.amount,
                type = safeType,
            )
            if (balanceVerdict == BalanceConsistency.Verdict.INCONSISTENT) {
                appLogger.warn(
                    "NLS",
                    "余额不自洽→待审: card=${aiItem.cardTail} balance=${aiItem.balanceAfterCents} " +
                        "amount=${aiItem.amount} type=$safeType text=${aiItem.balanceText}"
                )
            }

            val isComplete = aiItem.amount > 0 &&
                safeType in VALID_TYPES &&
                // 本地校验仍是必要条件（金额佐证/范围/类型），它拦的是「幻觉金额」
                validation.isValid &&
                !needsReview &&
                balanceVerdict != BalanceConsistency.Verdict.INCONSISTENT

            ParseOutcome.Parsed(
                ParsedTransaction(
                    capture = capture,
                    amount = aiItem.amount,
                    type = safeType,
                    categoryId = finalCategoryId,
                    categoryName = aiItem.categoryName,
                    categoryIcon = aiItem.categoryIcon,
                    // S3：商户名可作描述兜底（抽不到商户时为 null，不影响旧行为）
                    description = (aiItem.description ?: aiItem.merchant ?: aiItem.categoryName)
                        ?.let { sanitizeEcommerceDescription(it) },
                    score = clientCompletenessScore(aiItem),
                    isComplete = isComplete,
                    // 反馈闭环的钥匙：data 层（快捷解析/模型成功）优先，顶层兼容「模型空结果」形态。
                    // 两者都无 = 无可反馈对象（客户端不得凭空构造 id）。
                    parseLogId = response.data.parseLogId ?: response.parseLogId,
                )
            )
        } catch (e: HttpException) {
            if (e.code() == 400) {
                // 入参被服务端拒（空 / 超长）= **客户端请求问题**，不是「AI 挂了」：
                // 单独成桶（client_only.unparsed_input_invalid），也不记熔断失败
                //（否则一个客户端 bug 会把包名拉黑、静音真交易）。服务端 `docs/API.md` 附录 C §10。
                appLogger.warn(
                    "NLS",
                    "AI 入参被拒(400)，丢弃: pkg=${capture.packageName} msg=${e.message()}"
                )
                ParseOutcome.InvalidInput("http=400")
            } else {
                // B2：鉴权失败不能丢交易。
                // 2026-10-04：token 过期时 AI 抛 401，旧实现与其他异常一并
                // 「记一行日志就丢」，导致招行 20.00 / 交通卡 1.80 两笔真实交易
                // 静默消失，用户无感知。改为落库标记，用户重新登录后重放。
                circuitBreaker.recordOutcome(capture.packageName, success = false)
                if (e.code() == 401) {
                    appLogger.warn(
                        "NLS",
                        "AI 鉴权失败(401)，交易转入待补偿: pkg=${capture.packageName} text=${capture.fullText.take(60)}"
                    )
                    ParseOutcome.AuthExpired
                } else {
                    appLogger.error("NLS", "AI HTTP 异常 ${e.code()}: ${e.message()}")
                    Timber.w(e, "AI HTTP 失败（不可用）: ${capture.packageName}")
                    ParseOutcome.Unparsed("http=${e.code()}")
                }
            }
        } catch (e: CancellationException) {
            // 协程取消不是解析失败：吞掉会让服务重建/进程回收时落出一条假的 raw 留痕，
            // 并破坏结构化取消（取消后 Room 也会再抛一次）。
            throw e
        } catch (e: Exception) {
            circuitBreaker.recordOutcome(capture.packageName, success = false)
            appLogger.error("NLS", "AI异常: ${e.message}")
            Timber.w(e, "AI 解析失败，落原文: ${capture.packageName}")
            ParseOutcome.Unparsed(e.javaClass.simpleName)
        }
    }

    /**
     * 毫秒时间戳 → 带时区偏移的 ISO-8601（服务端要求，否则 400）。
     *
     * 用 `java.time` + 设备时区：服务端只取它的**北京日期**作为「今天」，
     * 因此这里如实表达「通知在这个设备的什么时候到达」即可。
     */
    private fun formatWithOffset(epochMillis: Long): String =
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * 把「服务端 022 顶层 `outcome` + 旧服务端 `code` 兼容」映射成失败桶。
     *
     * - `null` = 响应可用（`code == 0` 且 `data != null`，且不是失败 outcome）；
     * - [AiFailure.NOT_A_BILL] = AI/规则判定非账（正常结果，不计数、不记熔断失败）；
     * - [AiFailure.UNAVAILABLE] = AI 不可用/输出不可用（不确定，计入 `unparsed.*`）。
     *
     * ⚠️ 旧服务端（<022，无 `outcome`）兼容：能解码到这里的只有 2xx 响应，
     * 而 2xx + `code == 5001` 只可能是「模型空结果 = 判非账」（#6 超时是 504/502 →
     * Retrofit 招 `HttpException`，根本到不了这里）。`code == 5002` 是格式异常 → 不可用。
     */
    private fun classifyAiFailure(response: ApiResponse<AiParseResponseDto>): AiFailure? {
        when (response.outcome) {
            OUTCOME_NOT_A_BILL -> return AiFailure.NOT_A_BILL
            OUTCOME_UNAVAILABLE -> return AiFailure.UNAVAILABLE
        }
        if (response.code == 0 && response.data != null) return null
        return if (response.code == AI_EMPTY_RESULT_CODE) AiFailure.NOT_A_BILL else AiFailure.UNAVAILABLE
    }

    /** `/api/ai/parse` 失败的两种机械桶（与 [ParseOutcome] 的区分同构）。 */
    private enum class AiFailure { NOT_A_BILL, UNAVAILABLE }

    /**
     * 清理通知文本中的营销后缀（转发给 AI 的 `input` 就是清洗后的文本）。
     *
     * 实现已抽到 [com.aibill.android.domain.notification.MarketingSuffixCleaner]：
     * 预筛的 `precheck` 也要用**同一个串**算 `bare_amount` 长度（否则 30 字边界两端分叉）。
     */
    private fun cleanMarketingSuffix(text: String): String {
        val rules = rulesManager.getRules().processor
        return com.aibill.android.domain.notification.MarketingSuffixCleaner.clean(
            text = text,
            suffixCutoffs = rules.marketingSuffixCutoffs,
            commaKeywords = rules.marketingCommaKeywords,
        )
    }

    /**
     * 电商平台购物描述脱敏：将包含商品详情的描述简化为"XX购物"。
     * 避免具体购买商品名暴露在记账记录中。
     */
    private fun sanitizeEcommerceDescription(description: String): String {
        for ((keyword, replacement) in ECOMMERCE_DESCRIPTION_MAP) {
            if (description.contains(keyword)) {
                return replacement
            }
        }
        return description
    }

    /**
     * 消费「AI 响应侧多余条目」：新服务端用 [extraItemsDropped]（因 `max_items` 被裁掉的条数）
     * 告知；旧服务端不支持 `max_items`，仍可能回 [itemCount] > 1 条。
     *
     * 两种情形都**只取第一笔**（挑选标准在服务端，客户端不再自设第二套，确定性优先），
     * 并 warn + 计入 [CaptureFunnelStats] 的 AI 响应侧 stage——**绝不静默丢弃**。
     * stage 计的是「发生了这种感觉的响应」次数，条数只进 warn 日志；
     * 两种情况互斥（新服务端不会同时回多条），合并记一次避免双计。
     */
    private fun recordExtraItemsIfAny(capture: Capture, itemCount: Int, extraItemsDropped: Int) {
        val localExtra = (itemCount - 1).coerceAtLeast(0)
        if (localExtra > 0) {
            appLogger.warn(
                "NLS",
                "旧服务端返回多条(items=$itemCount)，确定性取第一笔，多余=$localExtra " +
                    "pkg=${capture.packageName}"
            )
        }
        if (extraItemsDropped > 0) {
            appLogger.warn(
                "NLS",
                "AI响应因 max_items 裁剪 $extraItemsDropped 条，仅取第一笔 " +
                    "pkg=${capture.packageName}"
            )
        }
        if (localExtra > 0 || extraItemsDropped > 0) {
            captureFunnelStats.record(CaptureFunnelStats.aiExtraItemsStage(capture.channel))
        }
    }

    /**
     * 客户端跨渠道视图比较用的**信息完整度分**
     * （给 [com.aibill.android.domain.notification.ScoredAdmission] 的 10s/60s 跨渠道择优）。
     *
     * ⚠️ **这不是挑选标准**：一次 AI 调用回哪一笔由服务端按 `max_items` 单一实现
     * （金额货币单位佐证 > 信息完整度 > 金额降序 > 模型原序）。客户端只取 `items.first()`，
     * 本函数保留纯粹因为「同金额、不同渠道两条候选」时要比一个信息完整度，
     * 与服务端挑选口径无关（这里只看得见 DTO 的机械字段，看不到佐证）。
     */
    private fun clientCompletenessScore(item: AiParsedItemDto): Int {
        var score = 0
        if (item.amount > 0) score += 1
        if (item.type in VALID_TYPES) score += 1
        if (item.categoryId != null && !item.categoryName.isNullOrBlank() &&
            item.categoryName.lowercase() !in GENERIC_CATEGORY_NAMES
        ) {
            score += 2
        }
        if (!item.description.isNullOrBlank()) score += 1
        return score
    }

    private companion object {
        val VALID_TYPES = setOf("expense", "income", "transfer")

        /** 服务端 022 顶层 `outcome`：AI/规则判非账（正常结果）。 */
        const val OUTCOME_NOT_A_BILL = "not_a_bill"

        /** 服务端 022 顶层 `outcome`：AI 不可用/输出不可用（失败）。 */
        const val OUTCOME_UNAVAILABLE = "unavailable"

        /** 「模型空结果」业务码（旧服务端无 `outcome` 时用它兼容判别）。 */
        const val AI_EMPTY_RESULT_CODE = 5001

        /** 「没分类」的几种写法：AI 给这些等于没给，不能算信息完整 */
        val GENERIC_CATEGORY_NAMES = setOf("其他", "其它", "未分类", "other")

        val ECOMMERCE_DESCRIPTION_MAP = mapOf(
            "拼多多" to "拼多多购物",
            "淘宝" to "淘宝购物",
            "天猫" to "天猫购物",
            "京东" to "京东购物",
            "闲鱼" to "闲鱼购物",
            "唯品会" to "唯品会购物",
            "得物" to "得物购物",
            "抖音商城" to "抖音购物",
            "抖音电商" to "抖音购物",
        )
    }
}
