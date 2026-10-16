package com.aibill.android.service

import com.aibill.android.data.notification.InMemoryNotificationRuleSource
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.domain.notification.rules.WechatRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.ProcessorRules
import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.notification.AiCircuitBreakerImpl
import com.aibill.android.data.notification.CaptureArchiveImpl
import com.aibill.android.data.notification.CaptureFunnelStatsImpl
import com.aibill.android.data.notification.NotificationInterpreterImpl
import com.aibill.android.data.notification.ScoredAdmissionImpl
import com.aibill.android.data.record.DirectRecordWriter
import com.aibill.android.data.record.RecordClock
import com.aibill.android.data.record.RecordIntakeImpl
import com.aibill.android.data.record.RecordMoment
import com.aibill.android.data.record.RecordWriter
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.confirmingTransactionApi
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.unconfirmedApiResponse
import com.aibill.android.domain.notification.CaptureArchive
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.util.AiResultValidator
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import java.io.IOException

/**
 * [NotificationProcessor] 作为**协调者**的测试：只断言**顺序与路由**——
 * 谁产出了、下一步写到哪、什么时候写。
 *
 * 各条策略自己的边界（窗口时刻、熔断窗口、畸形 AI 返回的降级、重放终态）在各自 module 的
 * 测试里直接打 interface：[com.aibill.android.data.notification.ScoredAdmissionTest] /
 * [com.aibill.android.data.notification.NotificationInterpreterTest] /
 * [com.aibill.android.data.notification.CaptureArchiveTest] /
 * [com.aibill.android.data.notification.AiCircuitBreakerTest]。
 * 本类不重复它们，也不断言任何私有状态。
 *
 * 这里刻意把**真实的**四个 module 与真实入账 module 接在一起跑：协调者的价值就在于接线正确，
 * 用 mock 掉再接一遍证明不了这件事。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationProcessorTest {

    private val testDispatcher = StandardTestDispatcher()

    private val context: Context = mockk(relaxed = true)
    private val aiApi: AiApi = mockk()
    private val aiResultValidator: AiResultValidator = mockk()
    private val notificationRecordDao: NotificationRecordDao = mockk(relaxed = true)
    private val pendingTransactionDao: PendingTransactionDao = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk()
    private val categoryLearningEngine: CategoryLearningEngine = mockk(relaxed = true)
    private val appLogger: AppLogger = mockk(relaxed = true)
    private lateinit var ruleSource: NotificationRuleSource
    private val streakTracker: com.aibill.android.domain.usecase.StreakTracker = mockk(relaxed = true)
    private val captureFunnelStats = CaptureFunnelStatsImpl()
    private lateinit var transactionApi: TransactionApi

    private lateinit var processor: NotificationProcessor
    private lateinit var inserted: MutableList<NotificationRecordEntity>

    private val baseTime = System.currentTimeMillis()

    @BeforeEach
    fun setUp() {
        io.mockk.mockkObject(com.aibill.android.service.WidgetDataUpdater)
        every { com.aibill.android.service.WidgetDataUpdater.notifyTransactionAdded(any(), any(), any(), any()) } returns Unit
        io.mockk.mockkObject(com.aibill.android.util.NotificationHelper)
        every { com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.aibill.android.util.NotificationHelper.showConfirmNotification(any(), any(), any(), any(), any(), any(), any()) } returns Unit

        every { userPreferences.aiParseEnabled } returns flowOf(true)
        every { userPreferences.notificationPrivacy } returns flowOf(true)
        coEvery { categoryLearningEngine.matchCategory(any()) } returns null
        transactionApi = confirmingTransactionApi()
        ruleSource = InMemoryNotificationRuleSource(
            NotificationRules(
                nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
                a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
                sms = SmsRules(emptyList()),
                sourceMapping = emptyMap(),
                processor = ProcessorRules(10, 60, listOf("点击领取"), listOf("红包", "领", "优惠"), 10_000_000, 1),
            )
        )
        coEvery { notificationRecordDao.findRecentSameAmount(any(), any()) } returns null
        inserted = mutableListOf()
        coEvery { notificationRecordDao.insert(capture(inserted)) } returns 1L

        val admission = ScoredAdmissionImpl(notificationRecordDao, ruleSource, appLogger)
        admission.testDispatcher = testDispatcher
        val interpreter = NotificationInterpreterImpl(
            aiApi = aiApi,
            aiResultValidator = aiResultValidator,
            circuitBreaker = AiCircuitBreakerImpl(appLogger),
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            appLogger = appLogger,
            ruleSource = ruleSource,
            balanceConsistency = com.aibill.android.data.notification.BalanceConsistencyImpl(),
            captureFunnelStats = captureFunnelStats,
        )
        processor = NotificationProcessor(
            context = context,
            interpreter = interpreter,
            scoredAdmission = admission,
            captureArchive = CaptureArchiveImpl(notificationRecordDao, appLogger),
            notificationRecordDao = notificationRecordDao,
            recordIntake = intake(),
            userPreferences = userPreferences,
            appLogger = appLogger,
            ruleSource = ruleSource,
            captureFunnelStats = captureFunnelStats,
        )
    }

    /** 组装真实的入账 module，只把落库换成 [DirectRecordWriter]（不包 Room 事务）。 */
    private fun intake(): RecordIntake {
        val writer: RecordWriter = DirectRecordWriter(pendingTransactionDao, notificationRecordDao)
        val clock = object : RecordClock {
            override fun now(): RecordMoment = RecordMoment.system()
            override fun at(epochMillis: Long) = RecordMoment.system().let { _ ->
                RecordMoment.of(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
            }
        }
        return RecordIntakeImpl(
            context = context,
            recordWriter = writer,
            transactionApi = transactionApi,
            recordClock = clock,
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            streakTracker = streakTracker,
            ruleSource = ruleSource,
        )
    }

    @AfterEach
    fun tearDown() {
        io.mockk.unmockkObject(com.aibill.android.service.WidgetDataUpdater)
        io.mockk.unmockkStatic(com.aibill.android.util.NotificationHelper::class)
        io.mockk.unmockkObject(com.aibill.android.util.NotificationHelper)
    }

    // ── Helpers ──

    private fun captureOf(
        packageName: String = "com.tencent.mm",
        text: String = "支付成功 ¥32.00",
        channel: SourceChannel = SourceChannel.NLS,
        receivedAt: Long = baseTime,
    ) = com.aibill.android.domain.notification.Capture(
        channel = channel,
        packageName = packageName,
        title = "微信支付",
        fullText = text,
        receivedAt = receivedAt,
    )

    private fun makeAiItem(
        type: String = "expense",
        amount: Int = 3200,
        categoryId: Int? = 7,
        categoryName: String? = "餐饮",
        description: String? = "午餐",
    ) = AiParsedItemDto(
        type = type,
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = "🍜",
        description = description,
        date = "2026-07-10",
        accountId = null,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
    )

    private fun aiOk(vararg items: AiParsedItemDto) = ApiResponse(
        code = 0,
        data = AiParseResponseDto(items = items.toList(), rawInput = "test"),
        message = "success",
    )

    private fun setupValidatorValid() {
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = true)
    }

    private fun httpErr(code: Int): HttpException =
        HttpException(retrofit2.Response.error<Any>(code, "{}".toResponseBody(null)))

    private fun rowsWithStatus(status: String) = inserted.filter { it.status == status }

    private fun verifyNoReceipt() {
        io.mockk.verify(exactly = 0) {
            com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        io.mockk.verify(exactly = 0) {
            com.aibill.android.util.NotificationHelper.showConfirmNotification(
                any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 路由：Parsed → 窗口 → 入账 / 待审
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("信息完整 + 服务端确认 → 先过 10s 评分窗口，再入账并发回执")
    fun `完整候选走窗口后入账`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem())
        setupValidatorValid()

        processor.process(captureOf())
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }

        advanceTimeBy(6_000)
        runCurrent()
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }
        io.mockk.verify(exactly = 1) {
            com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    @DisplayName("信息不完整 → 进待审池（status=parsed），一行缓存都不写")
    fun `信息不完整进待审`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem(categoryId = 99, categoryName = "其他"))
        setupValidatorValid()

        processor.process(captureOf(text = "支付成功 ¥50.00"))
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()

        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        assertEquals(1, rowsWithStatus("parsed").size)
        assertTrue(rowsWithStatus("parsed").single().parsedAmount == 3200)
        io.mockk.verify(exactly = 1) {
            com.aibill.android.util.NotificationHelper.showConfirmNotification(
                any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    @DisplayName("信息完整但校验不过（fail-closed）→ 也进待审池，不得静默丢弃")
    fun `校验不过进待审不丢弃`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem())
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = false, errors = listOf("金额佐证失败"))

        processor.process(captureOf(text = "支付成功 ¥99.00"))
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()

        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        assertEquals(1, rowsWithStatus("parsed").size, "校验不过必须留痕待审（fail-closed），不能当作「非账」丢掉")
    }

    @Test
    @DisplayName("信息完整但服务端没确认 → 也降级进待审池，且不发「已记录」回执")
    fun `服务端未确认降级待审`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem())
        setupValidatorValid()
        coEvery { transactionApi.createTransactions(any()) } returns unconfirmedApiResponse()

        processor.process(captureOf())
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()

        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        assertEquals(1, rowsWithStatus("parsed").size)
        io.mockk.verify(exactly = 0) {
            com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    @DisplayName("短时同额 = 一笔：同渠道同包名同金额 10s 内只入一条")
    fun `同来源同额合并只入一条`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem(amount = 1500, description = "咖啡"))
        setupValidatorValid()

        processor.process(captureOf(text = "支付成功 ¥15.00 咖啡"))
        runCurrent()
        processor.process(captureOf(text = "支付成功 ¥15.00 咖啡", receivedAt = baseTime + 2_000))
        runCurrent()

        // 改动前：第二条一到就把第一条立即提交 → 两条都入账（同一笔弹出两份）。
        // 现在：与跳渠道一样择优合并，窗口到期只产出一条。
        assertTrue(inserted.isEmpty(), "窗口未到期前不应入账，实际=$inserted")

        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(1, inserted.size, "短时同额只入一条，实际=$inserted")
    }

    // ═══════════════════════════════════════════════════════════════
    // 路由：解析失败 = 不确定（不落库 / 不打扰用户；只计数 + warn）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("outcome=unavailable → 不落库/不进待审/不发通知；计数 + warn（不确定就不打扰用户）")
    fun `解析失败不落库只计数`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 5002, data = null, message = "AI 返回格式异常", outcome = "unavailable",
        )

        processor.process(captureOf(text = "支付成功 ¥20.00 招行"))
        runCurrent()

        assertTrue(inserted.isEmpty(), "解析失败 = 不确定，不该落任何行（没有金额的原文用户没法审），实际=$inserted")
        assertEquals(
            1L,
            captureFunnelStats.snapshot().stages[
                CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS)
            ],
        )
        io.mockk.verify(exactly = 1) {
            appLogger.warn(
                "NLS",
                match { it.contains("解析失败，丢弃") && it.contains("com.tencent.mm") },
            )
        }
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        verifyNoReceipt()
    }

    @Test
    @DisplayName("outcome=not_a_bill（判非账）→ 不落库，也不计 unparsedTotal（判非账不是 AI 失败）")
    fun `判非账不算失败`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 5001, data = null, message = "AI 无法解析", outcome = "not_a_bill",
        )

        processor.process(captureOf(text = "点击领取优惠券，全场5折"))
        runCurrent()

        assertTrue(inserted.isEmpty(), "实际=$inserted")
        assertEquals(0L, captureFunnelStats.snapshot().unparsedTotal)
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        verifyNoReceipt()
    }

    @Test
    @DisplayName("网络异常 → 同样不落库，计数 +1")
    fun `网络异常不落库`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws IOException("timeout")

        processor.process(captureOf(text = "支付成功 ¥1.80 交通卡"))
        runCurrent()

        assertTrue(inserted.isEmpty(), "实际=$inserted")
        assertEquals(
            1L,
            captureFunnelStats.snapshot().stages[
                CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS)
            ],
        )
        verifyNoReceipt()
    }

    @Test
    @DisplayName("HTTP 400 入参非法 → 不落库；单独计 unparsed_input_invalid，不计 AI 失败")
    fun `入参非法单独成桶`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws httpErr(400)

        processor.process(captureOf(text = "支付成功 ¥20.00 招行"))
        runCurrent()

        assertTrue(inserted.isEmpty(), "实际=$inserted")
        assertEquals(1L, captureFunnelStats.snapshot().unparsedInputInvalidTotal)
        assertEquals(0L, captureFunnelStats.snapshot().unparsedAiFailedTotal)
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        verifyNoReceipt()
    }

    @Test
    @DisplayName("401 → 落 auth_pending（等重登重放），不计为解析失败")
    fun `401 落 auth_pending`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws httpErr(401)

        processor.process(captureOf(text = "支付成功 ¥20.00 招行"))
        runCurrent()

        assertEquals(1, rowsWithStatus(CaptureArchive.STATUS_AUTH_PENDING).size)
        assertEquals(0L, captureFunnelStats.snapshot().unparsedTotal)
        verifyNoReceipt()
    }

    @Test
    @DisplayName("AI 判定非账 / 关闭 AI → 什么都不写")
    fun `非账与关闭 AI 什么都不写`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns aiOk()
        processor.process(captureOf())
        runCurrent()

        every { userPreferences.aiParseEnabled } returns flowOf(false)
        processor.process(captureOf())
        runCurrent()

        assertTrue(inserted.isEmpty(), "既不是账也没解析失败 → 不该留任何行，实际=$inserted")
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 重放：auth_pending → 重走一遍 → 终态
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("replayAuthPending：原文重走解析，成功后正常入账，auth_pending 标终态")
    fun `重放待补偿`() = runTest(testDispatcher) {
        coEvery { notificationRecordDao.findAuthPending(any()) } returns listOf(
            NotificationRecordEntity(
                id = 7L,
                packageName = "cmb.pb",
                title = "招商银行",
                content = "扣款50.00元",
                status = CaptureArchive.STATUS_AUTH_PENDING,
                receivedAt = baseTime,
            )
        )
        coEvery { aiApi.parse(any()) } returns aiOk(makeAiItem(amount = 5000, description = "招行"))
        setupValidatorValid()

        processor.replayAuthPending()
        runCurrent()
        advanceTimeBy(11_000)
        runCurrent()

        coVerify(exactly = 1) { notificationRecordDao.updateStatus(7L, "auth_replayed", null) }
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }
    }
}
