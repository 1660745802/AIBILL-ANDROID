package com.aibill.android.presentation.ui.auth

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.repository.PendingTransactionRepositoryImpl
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `ServerConfigViewModel` 的回归测试，锁住「切换服务器前的二次确认」三件事：
 * 口径（含 failed）、弹框判定、以及 `onSave` 的清空行为。
 *
 * ## 测试边界为什么是 DAO 而不是 repository
 *
 * 本类用**真实** `PendingTransactionRepositoryImpl` + `mockk<PendingTransactionDao>`，
 * 而不是 `mockk<PendingTransactionRepository>()`。原因是一个可编译性事实：
 * domain 接口上**只有** `getAnyUnsyncedCount()`，`getPendingCount()` 只存在于
 * `PendingTransactionRepositoryImpl` 的委托里。真正的历史 bug 形态
 * 「domain 承诺 pending+failed，实现却委托 `dao.getPendingCount()`」
 * 若在接口层打桩，mock 会**遮住**这次委托，变异永远不会被发现。
 * 只有让被测链路穿过真实 impl，才能钉住那行委托。
 *
 * ## SQL 层口径另有覆盖，本文件只锁委托层与 ViewModel 决策
 *
 * `IN ('pending','failed')` 这条 SQL 本身不是本文件的职责——它由
 * `PendingTransactionDaoMarkFailedTest.kt:206-217` 用**真实 Room** 端到端验证
 * （那里同时钉住 `getPendingCount()` 确实不含 failed）。本文件不重复那层，
 * 只断言两件事：「impl 委托到了哪个 DAO 方法」+「ViewModel 拿到的数怎么变成弹框判定」。
 *
 * ## 框架红线
 *
 * 必须 Jupiter（`org.junit.jupiter.api.*`）。本工程 `app/build.gradle.kts` 用
 * `useJUnitPlatform()`，混用 JUnit4 的 `org.junit.Before` 会让生命周期钩子**静默不执行**
 * （见 `SyncWorkerSyncTransactionTest` 的历史教训）。
 *
 * 断言**依赖 `Dispatchers.setMain(UnconfinedTestDispatcher())` 的立即执行**：
 * `init {}` 与 `onSave()` 里的 `viewModelScope.launch` 不会排程挂起，构造/调用返回时
 * 状态就已写完，所以能直接 `assertEquals`、无需 `advanceUntilIdle()`。
 * **若将来换成 `StandardTestDispatcher`，每次 `onSave()` 之后必须补 `runCurrent()`，
 * 否则下述断言会因协程尚未执行而假红。**
 *
 * ## 不测什么
 *
 * - 不测 `onTestConnection()`——它会构造真实 OkHttpClient 发真实网络请求；
 *   不测 `isConnected`。
 * - **`ServerConfigScreen` 的分支走向零覆盖**：`:118-124`（无数据则直接保存、不弹框）、
 *   `:156-176`（确认框渲染）、`:168`（确认后才保存）**均无自动化测试**。
 *   本文件只测 `hasPendingData()` 这个**判定输入**，不覆盖弹框渲染与分支走向——
 *   即「判定为 true 就一定会弹」这一段仍是缺口（审计 P1-1，本轮不新建 Compose 测试）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigViewModelTest {

    private val userPreferences = mockk<UserPreferences>()
    private val pendingDao = mockk<PendingTransactionDao>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val accountRepository = mockk<AccountRepository>()

    @BeforeEach
    fun setUp() {
        // 必须先于 VM 构造：init {} 里的 viewModelScope.launch 要靠它才 eager 执行。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { userPreferences.serverUrl } returns flowOf(SERVER_URL)
        coEvery { userPreferences.setServerUrl(any()) } just runs

        // 双口径同时打桩：让「退回只数 pending」以断言失败而非 mock 未命中暴露出来。
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { pendingDao.getPendingCount() } returns 0
        coEvery { pendingDao.deleteAll() } just runs

        coEvery { categoryRepository.deleteAll() } just runs
        coEvery { accountRepository.deleteAll() } just runs
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 每个用例在**自己打完桩之后**才构造 VM——`init` 只读一次计数，
     * 若在 `@BeforeEach` 里统一构造，用例内改桩就来不及生效。
     */
    private fun buildViewModel() = ServerConfigViewModel(
        userPreferences = userPreferences,
        pendingTransactionRepository = PendingTransactionRepositoryImpl(pendingDao),
        categoryRepository = categoryRepository,
        accountRepository = accountRepository,
    )

    @Test
    fun `failed only - count uses pending+failed NOT pending-only, and that drives hasPendingData true`() =
        runTest {
            // 典型形态：唯一一条离线记录被 401 标成 failed，pending 归零。
            coEvery { pendingDao.getAnyUnsyncedCount() } returns 1
            coEvery { pendingDao.getPendingCount() } returns 0

            val viewModel = buildViewModel()

            // [口径] 数字来自 pending+failed，而非 pending-only
            assertEquals(1, viewModel.uiState.value.unsyncedCount)
            // [弹框判定] 有未同步数据 ⇒ 必须为 true，否则确认框不弹
            assertEquals(true, viewModel.hasPendingData())
            // [口径] impl 必须委托 getAnyUnsyncedCount
            coVerify(exactly = 1) { pendingDao.getAnyUnsyncedCount() }
            // [口径双保险] 一旦 impl 退回委托 dao.getPendingCount()，这行必红
            coVerify(exactly = 0) { pendingDao.getPendingCount() }
        }

    @Test
    fun `nothing unsynced - hasPendingData false so no dialog is needed`() = runTest {
        val viewModel = buildViewModel()

        assertEquals(0, viewModel.uiState.value.unsyncedCount)
        assertEquals(false, viewModel.hasPendingData())
    }

    @Test
    fun `onSave with clearLocalCache - clears exactly three tables and resets the count`() =
        runTest {
            coEvery { pendingDao.getAnyUnsyncedCount() } returns 3

            val viewModel = buildViewModel()
            assertEquals(true, viewModel.hasPendingData())

            viewModel.onSave(clearLocalCache = true)

            coVerify(exactly = 1) { pendingDao.deleteAll() }
            coVerify(exactly = 1) { categoryRepository.deleteAll() }
            coVerify(exactly = 1) { accountRepository.deleteAll() }
            coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
            // 清空后计数归零，二次确认不会再触发。
            assertEquals(false, viewModel.hasPendingData())
        }

    @Test
    fun `onSave without clearLocalCache - leaves local cache untouched but still saves url`() =
        runTest {
            coEvery { pendingDao.getAnyUnsyncedCount() } returns 2

            val viewModel = buildViewModel()

            viewModel.onSave(clearLocalCache = false)

            coVerify(exactly = 0) { pendingDao.deleteAll() }
            coVerify(exactly = 0) { categoryRepository.deleteAll() }
            coVerify(exactly = 0) { accountRepository.deleteAll() }
            coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
            // 不清空 ⇒ 计数原样保留。
            assertEquals(2, viewModel.uiState.value.unsyncedCount)
        }

    private companion object {
        const val SERVER_URL = "http://a.example/api"
    }
}
