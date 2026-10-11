package com.aibill.android.presentation.ui.home

import app.cash.turbine.test
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.NotificationRecordRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * `HomeViewModel` 对离线队列快照的转发不变量。
 *
 * ## 为什么是这个形态（mockk + Turbine，不写 fake 类）
 *
 * 与 `TransactionsViewModelTest` / `StatisticsViewModelTest` 同构：
 * `init {}` 会立即发起 4 路 `refresh()`，因此 6 个依赖全部要能被 init 安全打桩，
 * 否则构造阶段就会因 `no answer found` 失败。项目统一用 mockk，不为此新建 fake 实现——
 * fake 只是把同一份断言搬进另一层，属于「为了测试抽 shallow module」。
 *
 * ## 本文件锁什么
 *
 * 1. **快照只有一个字段**：`HomeUiState.syncStatus` 是 `SyncQueueStatus`，
 *    不是 `pendingSyncCount` + `failedSyncCount` 两个平行 Int。两个数各自漂移
 *    正是本切片要消灭的形态（历史 bug：名字说 unsynced、实现只数 pending）。
 * 2. **两个分区一起到达**：一次 emit 同时更新 pending 与 failed，
 *    不允许出现「pending 已更新、failed 还是旧值」的中间态。
 * 3. **refresh 不覆盖快照**：横幅数据只来自 `observeSyncStatus()`，
 *    月度/今日流水刷新不得顺手写它。
 *
 * SQL 层的分区语义由 `PendingTransactionDaoQueueStatusTest`（真实 Room）锁，
 * 本文件只锁转发——两层各管各的，缺一层都会留下 mock 覆盖不到的缝。
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
    private lateinit var notificationRepository: NotificationRecordRepository
    private lateinit var widgetSyncScheduler: WidgetSyncScheduler
    private lateinit var syncStatus: MutableStateFlow<SyncQueueStatus>
    private lateinit var viewModel: HomeViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        transactionRepository = mockk()
        categoryRepository = mockk()
        accountRepository = mockk()
        statsRepository = mockk()
        notificationRepository = mockk()
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

        // init → observePendingNotifications()：**保留的 notification 口径，与本切片无关**
        every { notificationRepository.observePendingCount() } returns flowOf(0)

        syncStatus = MutableStateFlow(SyncQueueStatus(pending = 0, failed = 0))
        every { transactionRepository.observeSyncStatus() } returns syncStatus

        viewModel = HomeViewModel(
            transactionRepository = transactionRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            statsRepository = statsRepository,
            notificationRepository = notificationRepository,
            widgetSyncScheduler = widgetSyncScheduler,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init collects the queue snapshot into uiState`() = runTest {
        assertEquals(SyncQueueStatus(pending = 0, failed = 0), viewModel.uiState.value.syncStatus)
        assertTrue(viewModel.uiState.value.syncStatus.isEmpty)
    }

    /** 一次 emit 必须同时落地两个分区，不允许中间态。 */
    @Test
    fun `snapshot updates both partitions in one emission`() = runTest {
        syncStatus.value = SyncQueueStatus(pending = 2, failed = 1)

        viewModel.uiState.test {
            assertEquals(SyncQueueStatus(pending = 2, failed = 1), awaitItem().syncStatus)
        }
    }

    /** 全部失败时快照仍然存在——历史缺口正是「failed 让横幅整体消失」。 */
    @Test
    fun `failed-only snapshot survives instead of collapsing to zero`() = runTest {
        syncStatus.value = SyncQueueStatus(pending = 0, failed = 3)

        val state = viewModel.uiState.value
        assertEquals(0, state.syncStatus.pending)
        assertEquals(3, state.syncStatus.failed)
        assertEquals(false, state.syncStatus.isEmpty)
    }

    /** 横幅数据只能来自 observeSyncStatus：refresh 不得覆盖它。 */
    @Test
    fun `refresh does not overwrite the queue snapshot`() = runTest {
        syncStatus.value = SyncQueueStatus(pending = 1, failed = 2)

        viewModel.refresh()

        assertEquals(SyncQueueStatus(pending = 1, failed = 2), viewModel.uiState.value.syncStatus)
    }
}
