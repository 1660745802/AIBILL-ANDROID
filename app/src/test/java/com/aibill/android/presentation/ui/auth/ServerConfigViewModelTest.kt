package com.aibill.android.presentation.ui.auth

import android.content.Context
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.queue.SyncQueueImpl
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalDataOwnership
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
 * 口径（含 failed）、弹框判定、以及 `onSave` 的处置意图。
 *
 * ## 测试边界为什么一半是 DAO、一半是 mock（架构候选 4 之后）
 *
 * - **计数仍走真实 impl + `mockk<PendingTransactionDao>`**：一个可编译性事实决定了这个选择——
 *   「未同步含 failed」这个口径写死在 `SyncQueueImpl` 委托的那一行 DAO 调用上，
 *   真实的历史 bug 形态「domain 承诺 pending+failed，实现却只数 pending」若在接口层打桩，
 *   mock 会**遮住**这次委托，变异永远不会被发现。本 ViewModel 仍然直接持有 `SyncQueue`
 *   取这个数，所以这层断言必须留着。
 * - **处置改成 mock `LocalDataOwnership` + 断言意图**：「清哪几张表」不再由本 ViewModel
 *   决定（收口前这里是手列的第二份清单，与 `AuthRepositoryImpl` 靠 KDoc 互保）。
 *   清单唯一落点是 `LocalDataOwnershipImpl`，那里用**真实 Room 按表名**钉住 4 张表——
 *   比收口前在这里断言 `NotificationRecordRepositoryImpl.deleteAll()` 委托对了没有更强：
 *   委托错方法 / 漏一张表 / 只清 3 张，都会红。
 *
 * ## SQL 层口径另有覆盖，本文件只锁委托层与 ViewModel 决策
 *
 * `IN ('pending','failed')` 这条 SQL 本身不是本文件的职责——它由
 * `PendingTransactionDaoMarkFailedTest.kt` 用**真实 Room** 端到端验证。
 * 收口前那里还用 `getPendingCount()` 与它对拍；该方法已在架构候选 3 删除
 * （生产零调用），pending-only 的唯一读法现在是 `observeSyncStatus()` 的那一列。
 * 本文件不重复那层，
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
 * - 不测「换服务器不做 SyncLock 握手」——那是归属 module 的事，
 *   由 `LocalDataOwnershipImplTest` 钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigViewModelTest {

    private val userPreferences = mockk<UserPreferences>()
    private val pendingDao = mockk<PendingTransactionDao>()
    private val ownership = mockk<LocalDataOwnership>()
    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        // 必须先于 VM 构造：init {} 里的 viewModelScope.launch 要靠它才 eager 执行。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { userPreferences.serverUrl } returns flowOf(SERVER_URL)
        coEvery { userPreferences.setServerUrl(any()) } just runs

        // 「未同步」口径含 failed。pending-only 的读法（dao.getPendingCount）已随架构候选 3
        // 删除，所以这里只有一个桩：impl 改数一次查询，下面的 coVerify 立刻红。
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { pendingDao.deleteAll() } just runs

        coEvery { ownership.dispose(any()) } returns DisposalOutcome.Cleared
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
        syncQueue = SyncQueueImpl(pendingDao, context),
        localDataOwnership = ownership,
    )

    @Test
    fun `failed only - count uses pending+failed NOT pending-only, and that drives hasPendingData true`() =
        runTest {
            // 典型形态：唯一一条离线记录被 401 标成 failed，pending 归零。
            coEvery { pendingDao.getAnyUnsyncedCount() } returns 1

            val viewModel = buildViewModel()

            // [口径] 数字来自 pending+failed，而非 pending-only
            assertEquals(1, viewModel.uiState.value.unsyncedCount)
            // [弹框判定] 有未同步数据 ⇒ 必须为 true，否则确认框不弹
            assertEquals(true, viewModel.hasPendingData())
            // [口径] impl 必须委托 getAnyUnsyncedCount
            coVerify(exactly = 1) { pendingDao.getAnyUnsyncedCount() }
        }

    @Test
    fun `nothing unsynced - hasPendingData false so no dialog is needed`() = runTest {
        val viewModel = buildViewModel()

        assertEquals(0, viewModel.uiState.value.unsyncedCount)
        assertEquals(false, viewModel.hasPendingData())
    }

    @Test
    fun `onSave with clearLocalCache - disposes as SwitchingServer, resets the count and saves the url`() =
        runTest {
            coEvery { pendingDao.getAnyUnsyncedCount() } returns 3

            val viewModel = buildViewModel()
            assertEquals(true, viewModel.hasPendingData())

            viewModel.onSave(clearLocalCache = true)

            // 本 ViewModel 只表达意图；清哪几张表由归属 module 兑现
            // （清单在 LocalDataOwnershipImpl，用真实 Room 按表名钉住）
            coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingServer) }
            // 换服务器绝不能走「同号重登」或「换号」意图（两者对数据的处置完全不同）
            coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
            coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SwitchingAccount) }
            coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
            // 清空后计数归零，二次确认不会再触发。
            assertEquals(false, viewModel.hasPendingData())
        }

    @Test
    fun `onSave without clearLocalCache - leaves local data untouched but still saves url`() = runTest {
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 2

        val viewModel = buildViewModel()

        viewModel.onSave(clearLocalCache = false)

        // 一张表都不许碰：连意图都不该发出去
        coVerify(exactly = 0) { ownership.dispose(any()) }
        coVerify(exactly = 0) { pendingDao.deleteAll() }
        coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
        // 不清空 ⇒ 计数原样保留。
        assertEquals(2, viewModel.uiState.value.unsyncedCount)
    }

    private companion object {
        const val SERVER_URL = "http://a.example/api"
    }
}
