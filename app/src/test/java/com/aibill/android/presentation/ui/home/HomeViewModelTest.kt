package com.aibill.android.presentation.ui.home

import app.cash.turbine.test
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.notification.ReviewQueue
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TransactionPage
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.service.WidgetSyncScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `HomeViewModel` 的转发不变量。
 *
 * ## 本文件不再有任何「队列快照 / 手动同步」用例
 *
 * 首页没有待同步横幅，也没有同步按钮——不存在「未同步」这个状态。
 * 横幅的四档状态机（Hidden / Pending / Failed / Mixed）与 `triggerSync()`
 * 已随离线队列一起移除，断言它们的用例一并删除（不计入基线）。
 *
 * ## 本文件锁什么
 *
 * 1. **init 的四路刷新互不干扰**：分类 / 账户 / 月度 / 今日流水并行，
 *    任意一路失败都只报错、不把别的字段带偏。
 * 2. **通知待确认数照常转发**：它是通知中心的入口计数，与同步无关。
 *
 * ## 为什么是这个形态（mockk + Turbine，不写 fake 类）
 *
 * 与 `TransactionsViewModelTest` / `StatisticsViewModelTest` 同构：
 * `init {}` 会立即发起 4 路 `refresh()`，因此 6 个依赖全部要能被 init 安全打桩，
 * 否则构造阶段就会因 `no answer found` 失败。项目统一用 mockk，不为此新建 fake 实现——
 * fake 只是把同一份断言搬进另一层，属于「为了测试抽 shallow module」。
 *
 * `Dispatchers.setMain` 必须在构造 ViewModel **之前**：`viewModelScope` 用
 * `Dispatchers.Main.immediate`，不替换会在构造阶段就抛
 * "Module with the Main dispatcher had failed to initialize"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var reviewQueue: ReviewQueue
    private lateinit var widgetSyncScheduler: WidgetSyncScheduler
    private lateinit var viewModel: HomeViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        transactionRepository = mockk()
        categoryRepository = mockk()
        accountRepository = mockk()
        statsRepository = mockk()
        reviewQueue = mockk()
        // 最终类 + 无挂起函数：relaxed 免去 stub scheduleMonthlyUpdate
        widgetSyncScheduler = mockk(relaxed = true)

        // init → refresh() 触达的四个网络/本地读
        coEvery { categoryRepository.syncCategories() } returns Result.Success(Unit)
        coEvery { accountRepository.syncAccounts() } returns Result.Success(Unit)
        coEvery { statsRepository.getSummary(any(), any()) } returns Result.Success(
            StatsSummary(expense = 0, income = 0, balance = 0, expenseChange = null),
        )
        coEvery {
            transactionRepository.getTransactions(any())
        } returns Result.Success(TransactionPage(items = emptyList(), total = 0))

        // init → observePendingNotifications()：通知中心入口计数
        every { reviewQueue.observePendingCount() } returns flowOf(3)

        viewModel = HomeViewModel(
            transactionRepository = transactionRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            statsRepository = statsRepository,
            reviewQueue = reviewQueue,
            widgetSyncScheduler = widgetSyncScheduler,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init collects the pending notification count into uiState`() = runTest {
        assertEquals(3, viewModel.uiState.value.pendingNotificationCount)
    }

    /** 今日流水按 clientId 去重：同一笔的多条视图只显示一次。 */
    @Test
    fun `today transactions are deduplicated by clientId`() = runTest {
        coEvery { transactionRepository.getTransactions(any()) } returns Result.Success(
            TransactionPage(
                items = listOf(tx("C1"), tx("C1"), tx("C2")),
                total = 3,
            ),
        )

        viewModel.refresh()

        assertEquals(listOf("C1", "C2"), viewModel.uiState.value.todayTransactions.map { it.clientId })
    }

    /** 今日流水失败只报错，不把状态里的其它字段带偏。 */
    @Test
    fun `today list failure surfaces an error event and leaves the state consistent`() = runTest {
        coEvery { transactionRepository.getTransactions(any()) } returns Result.Error(-1, "网络连接失败")

        // 先订阅再触发：`uiEvent` 是 replay=0 的 SharedFlow，晚订阅就收不到这一条
        viewModel.uiEvent.test {
            viewModel.refresh()

            val event = awaitItem()
            assertTrue(event is HomeViewModel.UiEvent.ShowError)
            assertEquals("加载今日流水失败，请检查网络", (event as HomeViewModel.UiEvent.ShowError).message)
        }
        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }
}

    private fun tx(clientId: String) = com.aibill.android.domain.model.Transaction(
        clientId = clientId,
        type = com.aibill.android.domain.model.TransactionType.EXPENSE,
        amount = 100,
        date = "2026-07-10",
    )
