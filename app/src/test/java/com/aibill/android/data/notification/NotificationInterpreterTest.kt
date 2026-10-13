package com.aibill.android.data.notification

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.ParseOutcome
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.service.A11yRules
import com.aibill.android.service.AlipayRules
import com.aibill.android.service.NlsRules
import com.aibill.android.service.NotificationRules
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.ProcessorRules
import com.aibill.android.service.SmsRules
import com.aibill.android.service.WechatRules
import com.aibill.android.util.AiResultValidator
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import java.io.IOException

/**
 * [NotificationInterpreter] 的接口级测试：直接打 [NotificationInterpreter.read]。
 *
 * 覆盖各类畸形/失败 AI 返回的降级：空 items、amount≤0、业务码失败、HTTP 5xx、401、
 * 网络异常、未知异常，以及校验/分类/评分/文本清洗这些「解析出什么」的细节。
 * **不测**去重、窗口、入库、原文留痕——那是别的 module。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationInterpreterTest {

    private val aiApi: AiApi = mockk()
    private val aiResultValidator: AiResultValidator = mockk()
    private val circuitBreaker: AiCircuitBreaker = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk()
    private val categoryLearningEngine: CategoryLearningEngine = mockk(relaxed = true)
    private val rulesManager: NotificationRulesManager = mockk(relaxed = true)
    private val appLogger: AppLogger = mockk(relaxed = true)
    private val captureFunnelStats = CaptureFunnelStatsImpl()

    private lateinit var interpreter: NotificationInterpreterImpl

    private val pkg = "com.tencent.mm"

    @BeforeEach
    fun setUp() {
        every { userPreferences.aiParseEnabled } returns flowOf(true)
        coEvery { categoryLearningEngine.matchCategory(any()) } returns null
        every { rulesManager.getRules() } returns rules()
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = true)
        interpreter = NotificationInterpreterImpl(
            aiApi = aiApi,
            aiResultValidator = aiResultValidator,
            circuitBreaker = circuitBreaker,
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            appLogger = appLogger,
            rulesManager = rulesManager,
            balanceConsistency = com.aibill.android.data.notification.BalanceConsistencyImpl(),
            captureFunnelStats = captureFunnelStats,
        )
    }

    private fun rules() = NotificationRules(
        nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
        a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
        sms = SmsRules(emptyList()),
        sourceMapping = emptyMap(),
        processor = ProcessorRules(
            scoringWindowSeconds = 10,
            dedupWindowSeconds = 60,
            marketingSuffixCutoffs = listOf("点击领取"),
            marketingCommaKeywords = listOf("红包", "领", "优惠"),
            maxAmountCents = 10_000_000,
            minAmountCents = 1,
        ),
    )

    private fun captureOf(text: String = "微信支付 已支付¥32.00 午餐") = Capture(
        channel = SourceChannel.NLS,
        packageName = pkg,
        title = "微信支付",
        fullText = text,
        receivedAt = 1_700_000_000_000L,
    )

    private fun aiItem(
        type: String = "expense",
        amount: Int = 3200,
        categoryId: Int? = 7,
        categoryName: String? = "餐饮",
        categoryIcon: String? = "🍜",
        description: String? = "午餐",
        merchant: String? = null,
        needsReview: Boolean? = null,
    ) = AiParsedItemDto(
        type = type,
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        description = description,
        merchant = merchant,
        needsReview = needsReview,
        date = "2026-07-10",
        accountId = null,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
    )

    private fun ok(vararg items: AiParsedItemDto) = ApiResponse(
        code = 0,
        data = AiParseResponseDto(items = items.toList(), rawInput = "test"),
        message = "success",
    )

    private fun httpErr(code: Int): HttpException =
        HttpException(retrofit2.Response.error<Any>(code, "{}".toResponseBody(null)))

    private fun parsed(outcome: ParseOutcome) = assertInstanceOf(ParseOutcome.Parsed::class.java, outcome).transaction

    /** AI 响应侧 stage 名（通知路径只有 NLS 用不到；测试都走 NLS）。 */
    private fun aiExtraStage() = CaptureFunnelStats.aiExtraItemsStage(SourceChannel.NLS)

    // ── 失败与降级 ──

    @Test
    @DisplayName("AI 判定非账（空 items）→ NotATransaction")
    fun `空 items 判定非账`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok()
        assertEquals(ParseOutcome.NotATransaction, interpreter.read(captureOf()))
        // AI 明确回了 200/空 items = 一次成功的 AI 调用，不能计入熔断失败；
        // 否则「AI 正常但判定非账」的界面会把包名拉黑（真交易被静音）。
        verify { circuitBreaker.recordOutcome(pkg, success = true, now = any()) }
        verify(exactly = 0) { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("AI 返回 amount<=0 → NotATransaction")
    fun `amount 非正判定非账`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem(amount = 0))
        assertEquals(ParseOutcome.NotATransaction, interpreter.read(captureOf()))
    }

    @Test
    @DisplayName("outcome=not_a_bill（模型空结果 code=5001）→ NotATransaction，不计熔断失败")
    fun `not_a_bill 判非账`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 5001, data = null, message = "AI 无法解析", outcome = "not_a_bill",
        )
        assertEquals(ParseOutcome.NotATransaction, interpreter.read(captureOf()))
        // 「判非账」= 一次成功的 AI 调用，不能记熔断失败（否则正常界面会把包名拉黑）
        verify { circuitBreaker.recordOutcome(pkg, success = true, now = any()) }
        verify(exactly = 0) { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("outcome=unavailable（格式异常 code=5002）→ Unparsed(code=5002)，计入熔断失败")
    fun `unavailable 是不可用`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 5002, data = null, message = "AI 返回格式异常", outcome = "unavailable",
        )
        assertEquals(ParseOutcome.Unparsed("code=5002"), interpreter.read(captureOf()))
        verify { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("旧服务端（无 outcome）code=5001 → NotATransaction（2xx 下只可能是模型空结果=判非账）")
    fun `旧服务端 5001 兼容判非账`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(code = 5001, data = null, message = "AI 解析失败")
        assertEquals(ParseOutcome.NotATransaction, interpreter.read(captureOf()))
        verify { circuitBreaker.recordOutcome(pkg, success = true, now = any()) }
    }

    @Test
    @DisplayName("旧服务端（无 outcome）code=5002 → Unparsed(code=5002)")
    fun `旧服务端 5002 兼容不可用`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(code = 5002, data = null, message = "格式异常")
        assertEquals(ParseOutcome.Unparsed("code=5002"), interpreter.read(captureOf()))
        verify { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("HTTP 400（入参空/超长）→ InvalidInput（单独成桶），不记熔断失败")
    fun `http 400 入参非法`() = runTest {
        coEvery { aiApi.parse(any()) } throws httpErr(400)
        assertEquals(ParseOutcome.InvalidInput("http=400"), interpreter.read(captureOf()))
        // 客户端请求问题不是「AI 挂了」：不得把包名拉黑
        verify(exactly = 0) { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("HTTP 5xx → Unparsed(http=500)")
    fun `HTTP 5xx`() = runTest {
        coEvery { aiApi.parse(any()) } throws httpErr(500)
        assertEquals(ParseOutcome.Unparsed("http=500"), interpreter.read(captureOf()))
        verify { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("401 → AuthExpired（会话过期，不是解析失败），并计入熔断失败")
    fun `401 会话过期`() = runTest {
        coEvery { aiApi.parse(any()) } throws httpErr(401)
        assertEquals(ParseOutcome.AuthExpired, interpreter.read(captureOf()))
        // 401 是真正的调用失败，必须上报熔断（否则 401 风暴不会触发静音）
        verify { circuitBreaker.recordOutcome(pkg, success = false, now = any()) }
    }

    @Test
    @DisplayName("网络异常（IOException）→ Unparsed")
    fun `网络异常`() = runTest {
        coEvery { aiApi.parse(any()) } throws IOException("timeout")
        assertEquals(ParseOutcome.Unparsed("IOException"), interpreter.read(captureOf()))
    }

    @Test
    @DisplayName("未知异常 → Unparsed（不能让异常穿出去丢掉通知）")
    fun `未知异常`() = runTest {
        coEvery { aiApi.parse(any()) } throws RuntimeException("boom")
        assertEquals(ParseOutcome.Unparsed("RuntimeException"), interpreter.read(captureOf()))
    }

    @Test
    @DisplayName("协程取消 → 原样向上抛，不吞成 Unparsed，也不记熔断失败")
    fun `取消原样抛出`() = runTest {
        coEvery { aiApi.parse(any()) } throws kotlinx.coroutines.CancellationException("cancelled")
        var thrown: Throwable? = null
        try {
            interpreter.read(captureOf())
        } catch (e: Throwable) {
            thrown = e
        }
        assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, thrown)
        verify(exactly = 0) { circuitBreaker.recordOutcome(any(), any(), any()) }
    }

    @Test
    @DisplayName("成功一次 → 上报熔断成功")
    fun `成功上报`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem())
        parsed(interpreter.read(captureOf()))
        verify { circuitBreaker.recordOutcome(pkg, success = true, now = any()) }
    }

    @Test
    @DisplayName("设置里关闭 AI 解析 → Disabled，且不调 AI / 不查学习 / 不记熔断")
    fun `关闭 AI 不调`() = runTest {
        every { userPreferences.aiParseEnabled } returns flowOf(false)
        assertEquals(ParseOutcome.Disabled, interpreter.read(captureOf()))
        coVerify(exactly = 0) { aiApi.parse(any()) }
        // 总闸必须排在分类学习之前：关掉 AI 就不该再读学习表，也不该产生熔断副作用
        coVerify(exactly = 0) { categoryLearningEngine.matchCategory(any()) }
        verify(exactly = 0) { circuitBreaker.recordOutcome(any(), any(), any()) }
    }

    // ── 解析出什么 ──

    @Test
    @DisplayName("type 非法 → 回落 expense")
    fun `type 回落`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem(type = "invalid_type"))
        assertEquals("expense", parsed(interpreter.read(captureOf())).type)
    }

    @Test
    @DisplayName("分类为「其他」→ 信息不完整（进待审）")
    fun `分类其他不完整`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem(categoryId = 99, categoryName = "其他"))
        assertFalse(parsed(interpreter.read(captureOf())).isComplete)
    }

    @Test
    @DisplayName("校验不过 → 信息不完整（进待审）")
    fun `校验不过不完整`() = runTest {
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = false, errors = listOf("金额佐证失败"))
        coEvery { aiApi.parse(any()) } returns ok(aiItem())
        assertFalse(parsed(interpreter.read(captureOf())).isComplete)
    }

    @Test
    @DisplayName("分类学习命中 → 用学到的分类，且信息完整")
    fun `学习命中`() = runTest {
        coEvery { categoryLearningEngine.matchCategory(any()) } returns 42
        coEvery { aiApi.parse(any()) } returns ok(aiItem(categoryId = null, categoryName = null))
        val tx = parsed(interpreter.read(captureOf()))
        assertEquals(42, tx.categoryId)
        assertTrue(tx.isComplete)
        // 关联键：学习表以「商家/描述」为键，必须拿**完整通知文本**去匹配；
        // 只用 title（常为「微信支付」这类泛化词）会让学习命中永远失效。
        coVerify(exactly = 1) { categoryLearningEngine.matchCategory("微信支付 已支付¥32.00 午餐") }
    }

    @Test
    @DisplayName("营销后缀在调 AI 前被截断（input 仍是清洗后的文本）")
    fun `营销后缀截断`() = runTest {
        val sent = slot<com.aibill.android.data.remote.dto.request.AiParseRequest>()
        coEvery { aiApi.parse(capture(sent)) } returns ok(aiItem())
        interpreter.read(captureOf(text = "微信支付 已支付¥32.00 点击领取红包"))
        assertEquals("微信支付 已支付¥32.00", sent.captured.input)
    }

    @Test
    @DisplayName("上下文随请求上送：channel/source/received_at(带偏移)/title/body")
    fun `请求带上下文`() = runTest {
        val sent = slot<com.aibill.android.data.remote.dto.request.AiParseRequest>()
        coEvery { aiApi.parse(capture(sent)) } returns ok(aiItem())

        interpreter.read(captureOf())

        val req = sent.captured
        assertEquals("nls", req.channel)
        assertEquals(pkg, req.source?.packageName)
        assertEquals("微信支付", req.title)
        // body = 未截断的原文；input = 清洗后（模型看到的）；佐证用 body 更全
        assertTrue(req.body!!.isNotBlank())
        // 带时区偏移的 ISO-8601（不带偏移服务端会 400）
        assertTrue(
            Regex(""".*[+-]\d{2}:\d{2}$""").matches(req.receivedAt ?: ""),
            "received_at 必须带时区偏移：${req.receivedAt}",
        )
    }

    @Test
    @DisplayName("电商商品名被脱敏为「XX购物」")
    fun `电商描述脱敏`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem(description = "拼多多 12包抽纸"))
        assertEquals("拼多多购物", parsed(interpreter.read(captureOf())).description)
    }

    @Test
    @DisplayName("通知路径请求不含 max_items（服务端已全局保证 items ≤ 1，死字段已删）")
    fun `通知请求不含 max_items`() = runTest {
        val sent = slot<AiParseRequest>()
        coEvery { aiApi.parse(capture(sent)) } returns ok(aiItem())
        interpreter.read(captureOf())
        assertFalse(
            sent.captured::class.java.declaredFields.any { it.name == "maxItems" },
            "服务端已全局保证单笔，AiParseRequest 不应再有 max_items",
        )
    }

    @Test
    @DisplayName("旧服务端回多条 → 确定性取第一笔 + warn + 计数（不再本地挑选、不静默丢）")
    fun `多条取第一笔并计数`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(
            aiItem(amount = 1000, categoryId = null, categoryName = null, description = null),
            aiItem(amount = 2000, description = "晚餐"),
        )
        val tx = parsed(interpreter.read(captureOf()))
        assertEquals(1000, tx.amount, "取第一笔，而不是本地评分最高的第二笔")
        assertEquals(1L, captureFunnelStats.snapshot().stages[aiExtraStage()])
        verify(exactly = 1) { appLogger.warn("NLS", match { it.contains("旧服务端返回多条") }) }
    }

    @Test
    @DisplayName("extra_items_dropped=2 → warn + 计数 +1，入账行为不变（取唯一那笔）")
    fun `服务端裁剪多余条目被计数`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 0,
            data = AiParseResponseDto(
                items = listOf(aiItem(amount = 3200)),
                rawInput = "x",
                extraItemsDropped = 2,
            ),
            message = "",
        )
        val tx = parsed(interpreter.read(captureOf()))
        assertEquals(3200, tx.amount)
        assertEquals(1L, captureFunnelStats.snapshot().stages[aiExtraStage()])
        verify(exactly = 1) { appLogger.warn("NLS", match { it.contains("裁剪 2 条") }) }
    }

    @Test
    @DisplayName("单笔且 extra_items_dropped=0 → 与改造前一致：无计数、无多余 warn")
    fun `单笔不计数`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(aiItem())
        val tx = parsed(interpreter.read(captureOf()))
        assertEquals(3200, tx.amount)
        assertTrue(captureFunnelStats.snapshot().stages.isEmpty(), captureFunnelStats.snapshot().stages.toString())
        verify(exactly = 0) {
            appLogger.warn("NLS", match { it.contains("多余") || it.contains("裁剪") })
        }
    }

    @Test
    @DisplayName("解析结果带上服务端日志 id（反馈闭环的钥匙）")
    fun `解析结果带 parseLogId`() = runTest {
        // data 层（快捷解析 / 模型成功）
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 0, data = AiParseResponseDto(items = listOf(aiItem()), rawInput = "x", parseLogId = 777L), message = "",
        )
        assertEquals(777L, parsed(interpreter.read(captureOf())).parseLogId)
    }

    @Test
    @DisplayName("顶层 parse_log_id 形态（模型空结果）也不丢 id；缺失时为 null")
    fun `顶层 parseLogId 与缺失形态`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 0, data = AiParseResponseDto(items = listOf(aiItem()), rawInput = "x"), message = "", parseLogId = 888L,
        )
        assertEquals(888L, parsed(interpreter.read(captureOf())).parseLogId)

        // 服务端预筛挡下的形态：没有任何日志 id → 必须是 null（不得凭空构造）
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 0, data = AiParseResponseDto(items = listOf(aiItem()), rawInput = "x"), message = "",
        )
        assertEquals(null, parsed(interpreter.read(captureOf())).parseLogId)
    }

    // ══════════════════════════════════════════════════════════
    // S3：服务端 needs_review 是唯一阈值（客户端不再自设第二套）
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("服务端 needs_review=true → 不进自动入账（isComplete=false）")
    fun `needs_review true 进待审`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(
            aiItem(amount = 3200, needsReview = true),
        )
        assertFalse(parsed(interpreter.read(captureOf())).isComplete)
    }

    @Test
    @DisplayName("服务端 needs_review=false → 自动入账（即使分类泛化）")
    fun `needs_review false 直接入账`() = runTest {
        // 分类是「其他」这类泛化值：按旧启発式会算信息不完整，但服务端已判定不需人工确认
        coEvery { aiApi.parse(any()) } returns ok(
            aiItem(amount = 3200, categoryName = "其他", needsReview = false),
        )
        assertTrue(parsed(interpreter.read(captureOf())).isComplete)
    }

    @Test
    @DisplayName("旧服务端（无 needs_review）→ 回退客户端启発式，行为与改造前一致")
    fun `needs_review 缺失时回退旧行为`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(
            aiItem(amount = 3200, categoryName = "其他", needsReview = null),
        )
        assertFalse(parsed(interpreter.read(captureOf())).isComplete, "分类泛化 → 旧行为是待审")
    }

    @Test
    @DisplayName("商户名可作描述兜底（AI 没给描述时）")
    fun `merchant 兜底描述`() = runTest {
        coEvery { aiApi.parse(any()) } returns ok(
            aiItem(amount = 3200, description = null, merchant = "星巴克", needsReview = false),
        )
        assertEquals("星巴克", parsed(interpreter.read(captureOf())).description)
    }
}
