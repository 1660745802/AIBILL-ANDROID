package com.aibill.android.presentation.ui.auth

import com.aibill.android.data.local.datastore.UserPreferences
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
 * `ServerConfigViewModel` 的回归测试：换服务器只表达**一句意图**，
 * 「清哪几张表」由归属 module 兑现。
 *
 * ## 本文件**不再**断言「未同步条数 / 二次确认」
 *
 * 不存在「未同步」这个状态：本地缓存的每一行都已被服务端确认
 * （见 `docs/ARCHITECTURE.md` §6.1），换服务器 = 换命名空间 = 整份丢掉，
 * 没有需要用户权衡的中间态，因此也没有那个弹框可测。
 *
 * ## 测试边界
 *
 * - **处置 = mock `LocalDataOwnership` + 断言意图**：清单唯一落点是
 *   `LocalDataOwnershipImpl`，那里用**真实 Room 按表名**钉住 4 张表——
 *   在这里断言委托只会遮住「漏一张表」这类变异。
 *
 * ## 框架红线
 *
 * 必须 Jupiter（`org.junit.jupiter.api.*`）。本工程 `app/build.gradle.kts` 用
 * `useJUnitPlatform()`，混用 JUnit4 的 `org.junit.Before` 会让生命周期钩子**静默不执行**
 * （见 `docs/ARCHITECTURE.md` §12.3 记录的那条历史教训）。
 *
 * 断言**依赖 `Dispatchers.setMain(UnconfinedTestDispatcher())` 的立即执行**：
 * `init {}` 与 `onSave()` 里的 `viewModelScope.launch` 不会排程挂起，构造/调用返回时
 * 状态就已写完，所以能直接 `assertEquals`、无需 `advanceUntilIdle()`。
 * **若将来换成 `StandardTestDispatcher`，每次 `onSave()` 之后必须补 `runCurrent()`。**
 *
 * ## 不测什么
 *
 * - 不测 `onTestConnection()`——它会构造真实 OkHttpClient 发真实网络请求。
 * - 不测「换服务器清哪几张表」——那是归属 module 的事，
 *   由 `LocalDataOwnershipImplTest` 用真实 Room 钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigViewModelTest {

    private val userPreferences = mockk<UserPreferences>()
    private val ownership = mockk<LocalDataOwnership>()

    @BeforeEach
    fun setUp() {
        // 必须先于 VM 构造：init {} 里的 viewModelScope.launch 要靠它才 eager 执行。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { userPreferences.serverUrl } returns flowOf(SERVER_URL)
        coEvery { userPreferences.setServerUrl(any()) } just runs
        coEvery { ownership.dispose(any()) } returns DisposalOutcome.Cleared
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = ServerConfigViewModel(
        userPreferences = userPreferences,
        localDataOwnership = ownership,
    )

    @Test
    fun `init reads the saved server url`() = runTest {
        val viewModel = buildViewModel()

        assertEquals(SERVER_URL, viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `onSave with clearLocalCache - disposes as SwitchingServer and saves the url`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSave(clearLocalCache = true)

        // 本 ViewModel 只表达意图；清哪几张表由归属 module 兑现
        // （清单在 LocalDataOwnershipImpl，用真实 Room 按表名钉住）
        coVerify(exactly = 1) { ownership.dispose(DisposalIntent.SwitchingServer) }
        // 换服务器绝不能走「同号重登」或「换号」意图（两者对数据的处置完全不同）
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SameAccountRelogin) }
        coVerify(exactly = 0) { ownership.dispose(DisposalIntent.SwitchingAccount) }
        coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
    }

    @Test
    fun `onSave without clearLocalCache - leaves local data untouched but still saves url`() =
        runTest {
            val viewModel = buildViewModel()

            viewModel.onSave(clearLocalCache = false)

            // 一张表都不许碰：连意图都不该发出去
            coVerify(exactly = 0) { ownership.dispose(any()) }
            coVerify(exactly = 1) { userPreferences.setServerUrl(SERVER_URL) }
        }

    private companion object {
        const val SERVER_URL = "http://a.example/api"
    }
}
