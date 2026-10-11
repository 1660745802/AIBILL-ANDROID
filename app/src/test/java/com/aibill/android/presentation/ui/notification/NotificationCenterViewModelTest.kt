package com.aibill.android.presentation.ui.notification

import app.cash.turbine.test
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.notification.ReviewQueue
import com.aibill.android.domain.record.RecordFailure
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.RecordOutcome
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 通知中心确认的**失败路径**：Toast 报错，且不许说「已确认」。
 *
 * 通知中心（单条/批量）确认走的是入账 module 的同一条路：服务端确认了才算记上。
 * 失败时审计行仍是 `parsed`（留在待审池，用户可再试），界面上只能看到失败文案。
 *
 * ## 为什么用 Robolectric
 *
 * `checkNlsStatus()` 在 `init` 里读 `Settings.Secure`（Android 静态方法），
 * 纯 JVM 单测下会抛 "not mocked"。这里只需要一个真实 `Application` 上下文，
 * 不需要 Compose。`application = Application::class` 是必需的：默认会按 manifest
 * 启动真实 `AiBillApp`，其 Hilt 图会构造 `TokenManager` → `AndroidKeyStore` 抛异常。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationCenterViewModelTest {

    private val reviewQueue: ReviewQueue = mockk(relaxed = true)
    private val recordIntake: RecordIntake = mockk()
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val learningEngine: CategoryLearningEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { reviewQueue.observe(any()) } returns flowOf(emptyList())
        every { reviewQueue.observePendingCount() } returns flowOf(0)
        every { categoryRepository.observeCategories(any()) } returns flowOf(emptyList())
        coEvery { transactionRepository.getTags() } returns Result.Success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = NotificationCenterViewModel(
        reviewQueue = reviewQueue,
        recordIntake = recordIntake,
        transactionRepository = transactionRepository,
        categoryRepository = categoryRepository,
        categoryLearningEngine = learningEngine,
        appContext = RuntimeEnvironment.getApplication(),
    )

    private fun parsedRecord(id: Long = 1L) = NotificationRecordEntity(
        id = id,
        packageName = "com.tencent.mm",
        content = "支付成功 ¥1.00",
        parsedAmount = 100,
        parsedType = "expense",
        parsedDescription = "午饭",
        status = "parsed",
    )

    @Test
    fun `confirm failure reports the failure instead of claiming it was recorded`() = runTest {
        coEvery { reviewQueue.findById(1L) } returns parsedRecord()
        coEvery { recordIntake.intake(any()) } returns RecordOutcome(
            clientId = "C-1",
            failure = RecordFailure.Rejected(422, "分类不存在"),
        )

        val viewModel = buildViewModel()
        viewModel.uiEvent.test {
            viewModel.confirmItem(1L)

            val event = awaitItem()
            assertEquals("记账失败：分类不存在", (event as NotificationCenterViewModel.UiEvent.ShowToast).message)
        }
        // 失败不学习：账都没记上，谈不上「用户改对了分类」
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }
    }

    @Test
    fun `confirm success reports the recorded outcome`() = runTest {
        coEvery { reviewQueue.findById(1L) } returns parsedRecord()
        coEvery { recordIntake.intake(any()) } returns RecordOutcome(clientId = "C-1")

        val viewModel = buildViewModel()
        viewModel.uiEvent.test {
            viewModel.confirmItem(1L)

            val event = awaitItem()
            assertEquals("已确认", (event as NotificationCenterViewModel.UiEvent.ShowToast).message)
        }
    }

    @Test
    fun `network failure surfaces the connectivity wording`() = runTest {
        coEvery { reviewQueue.findById(1L) } returns parsedRecord()
        coEvery { recordIntake.intake(any()) } returns RecordOutcome(
            clientId = "C-1",
            failure = RecordFailure.Offline,
        )

        val viewModel = buildViewModel()
        viewModel.uiEvent.test {
            viewModel.confirmItem(1L)

            val event = awaitItem()
            assertEquals("没网，记不上", (event as NotificationCenterViewModel.UiEvent.ShowToast).message)
        }
    }

    @Test
    fun `already confirmed records are not re-submitted`() = runTest {
        coEvery { reviewQueue.findById(1L) } returns parsedRecord().copy(status = "confirmed")

        val viewModel = buildViewModel()
        viewModel.uiEvent.test {
            viewModel.confirmItem(1L)

            val event = awaitItem()
            assertEquals("该笔已确认，请勿重复操作", (event as NotificationCenterViewModel.UiEvent.ShowToast).message)
        }
        coVerify(exactly = 0) { recordIntake.intake(any()) }
    }
}
