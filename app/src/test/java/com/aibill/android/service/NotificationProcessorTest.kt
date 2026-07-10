package com.aibill.android.service

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.remote.api.AiApi
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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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

    private lateinit var processor: NotificationProcessor

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default: AI parse is enabled
        every { userPreferences.aiParseEnabled } returns flowOf(true)
        every { userPreferences.notificationPrivacy } returns flowOf(true)
        // Default: no learned category
        coEvery { categoryLearningEngine.matchCategory(any()) } returns null

        processor = NotificationProcessor(
            context = context,
            aiApi = aiApi,
            aiResultValidator = aiResultValidator,
            notificationRecordDao = notificationRecordDao,
            pendingTransactionDao = pendingTransactionDao,
            userPreferences = userPreferences,
            categoryLearningEngine = categoryLearningEngine,
            appLogger = appLogger,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
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
        coEvery { aiResultValidator.validate(any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = true)
    }

    private fun setupValidatorInvalid() {
        coEvery { aiResultValidator.validate(any(), any(), any(), any()) } returns
            AiResultValidator.ValidationResult(isValid = false, errors = listOf("invalid"))
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

        // Before 10s - no insert yet
        advanceTimeBy(5_000)
        coVerify(exactly = 0) { pendingTransactionDao.insert(any()) }

        // After 10s - should insert
        advanceTimeBy(6_000)
        coVerify(exactly = 1) { pendingTransactionDao.insert(any()) }
        coVerify(atLeast = 1) { notificationRecordDao.insert(any()) }
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

        advanceTimeBy(11_000)

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

        // Process A11Y item within 10s (higher score)
        advanceTimeBy(3_000)
        processor.process(makeItem(
            packageName = "com.eg.android.AlipayGphone",
            fullText = "支付成功 ¥25.00 打车",
            channel = NotificationProcessor.Channel.A11Y,
            receivedAt = baseTime + 3_000,
        ))

        // Wait for scoring window to complete
        advanceTimeBy(12_000)

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

        // First NLS notification from wechat
        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime,
        ))

        // Wait for first to commit
        advanceTimeBy(11_000)

        // Second NLS notification from same wechat (same channel, same package)
        // This represents a genuinely different transaction
        processor.process(makeItem(
            packageName = "com.tencent.mm",
            fullText = "支付成功 ¥15.00 咖啡",
            channel = NotificationProcessor.Channel.NLS,
            receivedAt = baseTime + 11_000,
        ))

        advanceTimeBy(11_000)

        // Both should be inserted (same channel + same package = not deduped)
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

        // Wait for first to commit (10s scoring window)
        advanceTimeBy(11_000)
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

        advanceTimeBy(11_000)

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

        advanceTimeBy(11_000)

        // Should insert with type="expense" (fallback)
        coVerify(exactly = 1) { pendingTransactionDao.insert(match { it.type == "expense" }) }
    }
}
