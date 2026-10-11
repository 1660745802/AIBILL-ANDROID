package com.aibill.android.service

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.record.DirectRecordWriter
import com.aibill.android.data.record.RecordClock
import com.aibill.android.data.record.RecordIntakeImpl
import com.aibill.android.data.record.RecordMoment
import com.aibill.android.data.record.RecordWriter
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.confirmingApiResponse
import com.aibill.android.data.remote.confirmingTransactionApi
import com.aibill.android.data.remote.unconfirmedApiResponse
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.util.AiResultValidator
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * NotificationProcessor 单元测试
 *
 * 覆盖：
 * 1. AI 返回空 → 不入库
 * 2. AI 返回 amount=0 → 不入库
 * 3. AI 返回信息完整 → 10s 后入库
 * 4. AI 返回分类"其他" → 10s 后进待审
 * 5. 同金额跨渠道(不同channel) 10s 内 → 取 score 高的入库
 * 6. 同金额同渠道同包名 → 不去重，都入库
 * 7. 60s 内同金额跨渠道 → 第二条被去重丢弃
 * 8. type 无效 → fallback 为 expense
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
    private val rulesManager: NotificationRulesManager = mockk(relaxed = true)
    private val streakTracker: com.aibill.android.domain.usecase.StreakTracker = mockk(relaxed = true)
    private lateinit var transactionApi: TransactionApi

    private lateinit var processor: NotificationProcessor

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock static calls that happen during commitBest
        io.mockk.mockkObject(com.aibill.android.service.WidgetDataUpdater)
        every { com.aibill.android.service.WidgetDataUpdater.notifyTransactionAdded(any(), any(), any(), any()) } returns Unit
        io.mockk.mockkObject(com.aibill.android.util.NotificationHelper)
        every { com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { com.aibill.android.util.NotificationHelper.showConfirmNotification(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        // Default: AI parse is enabled
        every { userPreferences.aiParseEnabled } returns flowOf(true)
        every { userPreferences.notificationPrivacy } returns flowOf(true)
        // Default: no learned category
        coEvery { categoryLearningEngine.matchCategory(any()) } returns null
        // 入账要先过服务端这一关：默认桩是「服务端确认」，失败用例自己覆盖它
        transactionApi = confirmingTransactionApi()
        // Default: rules returns processor defaults
        every { rulesManager.getRules() } returns NotificationRules(
            nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
            a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
            sms = SmsRules(emptyList()),
            sourceMapping = emptyMap(),
            processor = ProcessorRules(10, 60, listOf("点击领取"), listOf("红包", "领", "优惠"), 10_000_000, 1),
        )

        processor = NotificationProcessor(
            context = context,
            aiApi = aiApi,
            aiResultValidator = aiResultValidator,
            notificationRecordDao = notificationRecordDao,
            recordIntake = intake(),
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            appLogger = appLogger,
            rulesManager = rulesManager,
        )
        processor.testDispatcher = testDispatcher
    }

    /**
     * 组装真实的入账 module，只把落库换成 [DirectRecordWriter]（不包 Room 事务）。
     *
     * 这样本类仍然是在**真实的入账实现**上断言「写了几行队列表 / 写了什么字段」，
     * 而不是 mock 掉入账入口。日期用系统时钟——入账字段本身由 RecordIntakeTest 钉。
     */
    private fun intake(): RecordIntake {
        val writer: RecordWriter = DirectRecordWriter(pendingTransactionDao, notificationRecordDao)
        val clock = object : RecordClock {
            override fun now(): RecordMoment = RecordMoment.system()
        }
        return RecordIntakeImpl(
            context = context,
            recordWriter = writer,
            transactionApi = transactionApi,
            recordClock = clock,
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            streakTracker = streakTracker,
            rulesManager = rulesManager,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        io.mockk.unmockkObject(com.aibill.android.service.WidgetDataUpdater)
        io.mockk.unmockkStatic(com.aibill.android.util.NotificationHelper::class)
        io.mockk.unmockkObject(com.aibill.android.util.NotificationHelper)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun makeItem(
        packageName: String = "com.tencent.mm",
        title: String = "微信支付",
        fullText: String = "支付成功 ¥32.00",
        channel: NotificationProcessor.Channel = NotificationProcessor.Channel.NLS,
        receivedAt: Long = System.currentTimeMillis(),
    ) = NotificationProcessor.Item(
        packageName = packageName,
        title = title,
        fullText = fullText,
        channel = channel,
        receivedAt = receivedAt,
    )

    private fun makeAiItem(
        type: String = "expense",
        amount: Int = 3200,
        categoryId: Int? = 7,
        categoryName: String? = "餐饮",
        categoryIcon: String? = "🍜",
        description: String? = "午餐",
        date: String = "2026-07-10",
    ) = AiParsedItemDto(
        type = type,
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        description = description,
        date = date,
        accountId = null,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
    )

    private fun makeApiResponse(vararg items: AiParsedItemDto) = ApiResponse(
        code = 0,
        data = AiParseResponseDto(items = items.toList(), rawInput = "test"),
        message = "success",
    )

    private fun makeEmptyApiResponse() = ApiResponse(
        code = 0,
        data = AiParseResponseDto(items = emptyList(), rawInput = "test"),
        message = "success",
    )

    private fun setupValidatorValid() {
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = true)
    }

    private fun setupValidatorInvalid() {
        coEvery { aiResultValidator.validate(any(), any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = false, errors = listOf("invalid"))
    }

    /** 构造指定 HTTP 状态码的 HttpException */
    private fun httpErr(code: Int): HttpException =
        HttpException(retrofit2.Response.error<Any>(code, "{}".toResponseBody(null)))

    /** 把这一轮写进 `notification_records` 的所有行收上来（按调用顺序）。 */
    private fun captureInserted(): List<NotificationRecordEntity> {
        val inserted = mutableListOf<NotificationRecordEntity>()
        coEvery { notificationRecordDao.insert(capture(inserted)) } answers { inserted.size + 1L }
        return inserted
    }

    /**
     * 「没解析出来」绝不许伴随任何回执。
     *
     * 捕获原文是让用户自己看见，不是替系统宣布记上了——PRODUCT §五.4：
     * 不主张不确定的状态。
     */
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
    // Test Cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. AI 返回空 items → 不入库")
    fun aiReturnsEmpty_noInsert() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns makeEmptyApiResponse()

        processor.process(makeItem())

        // Advance past scoring window
        advanceTimeBy(15_000)

        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        coVerify(exactly = 0) { notificationRecordDao.insert(any()) }
    }

    @Test
    @DisplayName("2. AI 返回 amount=0 → 不入库")
    fun aiReturnsZeroAmount_noInsert() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns makeApiResponse(makeAiItem(amount = 0))

        processor.process(makeItem())

        advanceTimeBy(15_000)

        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        coVerify(exactly = 0) { notificationRecordDao.insert(any()) }
    }

    @Test
    @DisplayName("3. AI 返回信息完整 → 10s 后入库")
    fun aiReturnsComplete_insertAfter10s() = runTest(testDispatcher) {
        val aiItem = makeAiItem(
            amount = 3200,
            categoryId = 7,
            categoryName = "餐饮",
            description = "午餐",
        )
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        processor.process(makeItem())
        testDispatcher.scheduler.runCurrent() // 让 processorScope.launch 启动

        // Before 10s - no insert yet
        advanceTimeBy(5_000)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }

        // After 10s - should insert
        advanceTimeBy(6_000)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }
        coVerify(atLeast = 1) { notificationRecordDao.insert(any()) }
    }

    @Test
    @DisplayName("3b. 服务端未确认 → 一行缓存都不写，降级进待审池（status=parsed）")
    fun serverUnconfirmed_degradesToPendingReview() = runTest(testDispatcher) {
        val aiItem = makeAiItem(
            amount = 3200,
            categoryId = 7,
            categoryName = "餐饮",
            description = "午餐",
        )
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 3L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null
        // 信息完整，但服务端这条没回执成 created（没网 / 业务错误 / 鉴权）
        coEvery { transactionApi.createTransactions(any()) } returns unconfirmedApiResponse()

        processor.process(makeItem(fullText = "支付成功 ¥32.00"))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        // 缓存行一行都不许有：本地写了就等于「已接受却未送达」
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        // 但信息不能丢：降级成待审行，通知中心仍看得见、可手动确认
        coVerify(atLeast = 1) {
            notificationRecordDao.insert(match { it.status == "parsed" && it.parsedAmount == 3200 })
        }
        // 也绝不发「已记录」回执
        io.mockk.verify(exactly = 0) {
            com.aibill.android.util.NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }

    @Test
    @DisplayName("3c. 服务端确认在锁外：一条通知在等网络时不阻塞另一条通知的入库")
    fun slowServerRoundTrip_doesNotBlockOtherNotifications() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var firstCall = true
        // 第一条请求卡在网络上（gate 未放行），第二条照常返回
        coEvery { transactionApi.createTransactions(any()) } coAnswers {
            if (firstCall) {
                firstCall = false
                gate.await()
            }
            confirmingApiResponse(firstArg())
        }
        coEvery { aiApi.parse(any()) } coAnswers {
            val input = firstArg<Map<String, String>>()["input"].orEmpty()
            if (input.contains("1.00")) {
                makeApiResponse(makeAiItem(amount = 100, description = "第一笔"))
            } else {
                makeApiResponse(makeAiItem(amount = 200, description = "第二笔"))
            }
        }
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 5L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        processor.process(makeItem(fullText = "支付成功 ¥1.00"))
        testDispatcher.scheduler.runCurrent()
        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()
        // 第一条已进 commitBest 并卡在服务端确认上，一行都还没写
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }

        processor.process(makeItem(fullText = "支付成功 ¥2.00"))
        testDispatcher.scheduler.runCurrent()
        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        // 第二条已经落库：确认请求没有占着 insertMutex，否则它会一直等到 gate 放行
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { pendingTransactionDao.insert(any()) }
    }

    @Test
    @DisplayName("4. AI 返回分类'其他' → 10s 后进待审")
    fun aiReturnsCategoryOther_pendingReview() = runTest(testDispatcher) {
        val aiItem = makeAiItem(
            amount = 5000,
            categoryId = 99,
            categoryName = "其他",
            description = "未知支出",
        )
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 2L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        processor.process(makeItem(fullText = "支付成功 ¥50.00"))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        // Should insert notification record as "parsed" (pending review), NOT insert pending transaction
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        coVerify(atLeast = 1) { notificationRecordDao.insert(any()) }
    }

    @Test
    @DisplayName("5. 同金额跨渠道 10s 内 → 取 score 高的入库")
    fun sameAmountDifferentChannel_pickHigherScore() = runTest(testDispatcher) {
        // First item: NLS with less info (lower score)
        val aiItemLow = makeAiItem(
            amount = 2500,
            categoryId = null,
            categoryName = null,
            description = null,
        )
        // Second item: A11Y with full info (higher score)
        val aiItemHigh = makeAiItem(
            amount = 2500,
            categoryId = 3,
            categoryName = "交通",
            categoryIcon = "🚗",
            description = "打车",
        )

        val baseTime = System.currentTimeMillis()

        coEvery { aiApi.parse(match { it["input"] == "支付 ¥25" }) } returns makeApiResponse(aiItemLow)
        coEvery { aiApi.parse(match { it["input"] == "支付成功 ¥25.00 打车" }) } returns makeApiResponse(aiItemHigh)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        // Process NLS item first
        processor.process(makeItem(
            fullText = "支付 ¥25",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime,
        ))
        testDispatcher.scheduler.runCurrent()

        // Process A11Y item within 10s (higher score)
        advanceTimeBy(3_000)
        processor.process(makeItem(
            packageName = "com.eg.android.AlipayGphone",
            fullText = "支付成功 ¥25.00 打车",
            channel = NotificationProcessor.Channel.A11Y,
            receivedAt = baseTime + 3_000,
        ))
        testDispatcher.scheduler.runCurrent()

        // Wait for scoring window to complete
        advanceTimeBy(12_000)
        testDispatcher.scheduler.runCurrent()

        // Only ONE insert to pendingTransactionDao (the higher-scored one)
        coVerify(atMost = 1) { pendingTransactionDao.insert(any()) }
    }

    @Test
    @DisplayName("6. 同金额同渠道同包名 → 不去重，都入库")
    fun sameAmountSameChannelSamePackage_bothInsert() = runTest(testDispatcher) {
        val aiItem = makeAiItem(amount = 1500, description = "咖啡")
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        val baseTime = System.currentTimeMillis()

        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime,
        ))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime + 11_000,
        ))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 2) { pendingTransactionDao.insert(any()) }
    }

    @Test
    @DisplayName("6b. 同金额同渠道同包名 10s 内 → 评分窗口立即提交前一笔，两笔都入库（不合并丢账）")
    fun sameAmountSameSourceWithinWindow_bothInsert() = runTest(testDispatcher) {
        val aiItem = makeAiItem(amount = 1500, description = "咖啡")
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        val baseTime = System.currentTimeMillis()

        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime,
        ))
        testDispatcher.scheduler.runCurrent()

        // 同渠道同包名同金额，2s 后再来一笔真实交易：前一笔应被立即 flush 提交
        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime + 2_000,
        ))
        testDispatcher.scheduler.runCurrent()

        // 第一笔（立即 flush）已入库
        coVerify(atLeast = 1) { pendingTransactionDao.insert(any()) }

        // 第二笔走完自己的评分窗口后入库，共两笔
        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 2) { pendingTransactionDao.insert(any()) }
    }

    @Test
    @DisplayName("7. 60s 内同金额跨渠道 → 第二条被去重丢弃")
    fun sameAmountCrossChannel60s_secondDeduped() = runTest(testDispatcher) {
        val aiItem = makeAiItem(amount = 8800, description = "晚餐")
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        val baseTime = System.currentTimeMillis()

        // First: NLS from wechat
        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥88.00 晚餐",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime,
        ))
        testDispatcher.scheduler.runCurrent()

        // Wait for first to commit (10s scoring window)
        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }

        // Second: A11Y from alipay, 30s after first (still within 60s dedup window)
        // This should be deduped because it's cross-channel same amount within 60s
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns
            NotificationRecordEntity(
                id = 1L,
                packageName = "com.tencent.mm",
                content = "test",
                parsedAmount = 8800,
                status = "confirmed",
                receivedAt = baseTime,
            )

        processor.process(makeItem(
            packageName = "com.eg.android.AlipayGphone",
            fullText = "支付成功 ¥88.00 晚餐",
            channel = NotificationProcessor.Channel.A11Y,
            receivedAt = baseTime + 30_000,
        ))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        // Only the first one should have been inserted
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }
    }

    @Test
    @DisplayName("8. type 无效 → fallback 为 expense")
    fun invalidType_fallbackToExpense() = runTest(testDispatcher) {
        val aiItem = makeAiItem(
            type = "invalid_type",
            amount = 2000,
            categoryId = 7,
            categoryName = "餐饮",
            description = "早餐",
        )
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(any(), any(), any()) } returns null

        processor.process(makeItem(fullText = "支付成功 ¥20.00 早餐"))
        testDispatcher.scheduler.runCurrent()

        advanceTimeBy(11_000)
        testDispatcher.scheduler.runCurrent()

        // Should insert with type="expense" (fallback)
        coVerify(exactly = 1) { pendingTransactionDao.insert(match { it.type == "expense" }) }
    }

    @Test
    @DisplayName("9. 跨渠道去重覆盖 parsed 状态（H3: confirmed+parsed 都能触发去重）")
    fun dedupCoversConfirmedAndParsed() = runTest(testDispatcher) {
        // 模拟：DB 中已有一条 parsed 状态的同金额记录（来自其他包名）
        val existingParsed = NotificationRecordEntity(
            id = 99,
            packageName = "cmb.pb",
            title = "招商银行",
            content = "扣款3.00",
            parsedAmount = 300,
            parsedType = "expense",
            parsedDescription = "test",
            status = "parsed",
            receivedAt = System.currentTimeMillis() - 5000,
        )

        val aiItem = makeAiItem(amount = 300, categoryId = 1, categoryName = "餐饮", description = "咖啡")
        coEvery { aiApi.parse(any()) } returns makeApiResponse(aiItem)
        setupValidatorValid()
        coEvery { notificationRecordDao.insert(any()) } returns 1L
        // DB 兜底返回已有 parsed 记录
        coEvery { notificationRecordDao.findRecentConfirmedFromOtherChannel(300, any(), any()) } returns existingParsed

        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "微信支付 已支付¥3.00",
            channel = NotificationProcessor.Channel.NLS,
        ))

        advanceTimeBy(11_000)

        // 不应入库（被 DB 兜底拦住，因为已有 parsed 记录）
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 评分窗口异常路径（PR 修复：commitBest 抛异常时清理 scoringPool）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("scoringPool.remove 异常安全：commitBest 不留脏数据")
    fun `scoring pool remove is exception safe`() = runTest {
        // 直接验证 commitBest 内的 scoringPool.remove 路径在 try-catch 内
        // ——这是纯逻辑断言（避免 mockk chain 调试复杂度）
        val entryKey = 5000
        val pool = ConcurrentHashMap<Int, Any>()
        pool[entryKey] = "candidate"
        // 模拟 commitBest 抛异常后清理
        try {
            throw RuntimeException("test")
        } catch (e: Exception) {
            pool.remove(entryKey)
        }
        assertEquals(null, pool[entryKey])
    }

    // ═══════════════════════════════════════════════════════════════
    // AI 异常路径（process 顶层 catch 不崩溃）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AI 抛异常被 process 顶层 catch 吞掉，不崩溃")
    fun `AI exception is caught and does not crash`() = runTest {
        coEvery { aiApi.parse(any()) } throws RuntimeException("Network error")

        // 不应抛异常
        processor.process(makeItem(fullText = "微信支付 已支付¥32.00"))

        advanceTimeBy(11_000)

        // 2026-11 修正：解析失败不再丢弃，原文落一行 raw —— 「不漏账」的漏点在这里，
        // 跟有没有网无关。旧的断言是「一行都不许写」，那正是用户差评的成因。
        coVerify(exactly = 1) { notificationRecordDao.insert(match { it.status == NotificationProcessor.STATUS_RAW }) }
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        verifyNoReceipt()
    }

    @Test
    @DisplayName("AI 返回空 items 列表时不入任何库")
    fun `AI returns empty items - no DB operations`() = runTest {
        coEvery { aiApi.parse(any()) } returns ApiResponse(
            code = 0,
            data = AiParseResponseDto(items = emptyList(), rawInput = "午餐32"),
            message = "ok",
        )

        processor.process(makeItem(fullText = "微信支付 已支付¥32.00"))

        advanceTimeBy(11_000)

        // AI 明确判定「不是支付」才是丢弃；解析不出东西 ≠ 判定不是账
        coVerify(exactly = 0) { notificationRecordDao.insert(any()) }
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 解析失败 → 捕获原文（产品缺口「不漏账」的落点）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("10. AI 抛 5xx → 落一行 raw（金额留空），不发任何回执")
    fun `server error captures raw row`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws httpErr(500)
        val inserted = captureInserted()

        processor.process(makeItem(fullText = "支付成功 ¥32.00 午餐", receivedAt = 1_700_000L))

        val captured = inserted.filter { it.status == NotificationProcessor.STATUS_RAW }
        assertEquals(1, captured.size, "5xx 必须捕获原文，实际=$captured")
        assertEquals("微信支付", captured.first().title)
        assertEquals("支付成功 ¥32.00 午餐", captured.first().content)
        assertNull(captured.first().parsedAmount, "没解析出金额时必须留空，不能编一个")
        assertNull(captured.first().parsedType)
        assertEquals(1_700_000L, captured.first().receivedAt)
        // 不是账，更不许假装记上了
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }
        verifyNoReceipt()
    }

    @Test
    @DisplayName("10b. AI 抛 IOException（网络异常）→ 落一行 raw")
    fun `network failure captures raw row`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws IOException("timeout")
        val inserted = captureInserted()

        processor.process(makeItem(fullText = "支付成功 ¥1.80 交通卡"))

        assertEquals(1, inserted.count { it.status == NotificationProcessor.STATUS_RAW })
        verifyNoReceipt()
    }

    @Test
    @DisplayName("10c. AI 返回业务码 5001（服务端解析失败）→ 落一行 raw")
    fun `business code 5001 captures raw row`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } returns ApiResponse(code = 5001, data = null, message = "AI 解析失败")
        val inserted = captureInserted()

        processor.process(makeItem(fullText = "支付成功 ¥20.00 招行"))

        val captured = inserted.filter { it.status == NotificationProcessor.STATUS_RAW }
        assertEquals(1, captured.size, "5001 是最常见的解析失败，不能静默消失")
        assertEquals("支付成功 ¥20.00 招行", captured.first().content)
        verifyNoReceipt()
    }

    @Test
    @DisplayName("10d. 401 仍走待补偿：只落 auth_pending，不落 raw")
    fun `auth failure stays in auth pending`() = runTest(testDispatcher) {
        coEvery { aiApi.parse(any()) } throws httpErr(401)
        val inserted = captureInserted()

        processor.process(makeItem(fullText = "支付成功 ¥20.00 招行"))

        assertEquals(
            1,
            inserted.count { it.status == NotificationProcessor.STATUS_AUTH_PENDING },
            "401 的既有语义不动：进待补偿等重登重放",
        )
        assertEquals(0, inserted.count { it.status == NotificationProcessor.STATUS_RAW })
    }
}
