package com.aibill.android.presentation.ui.record

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.AiRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TemplateRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.StreakTracker
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 手工记账的**失败路径**：报错 + 保留表单输入，且不落任何本地行。
 *
 * ## 这条路径存在的理由
 *
 * 手工记账的成功判据在服务端手里：`createTransactions` 只认
 * 「`created[]` 里按 `client_id` 匹配到 id」的 [Result.Success]（判据本身由
 * `TransactionRepositoryCreateTest` 钉）。失败时**不清表单、不落库、不报「已记录」**——
 * 用户原地改一改就能重试。无网与业务失败分两句文案：无网是「没网，记不上」，
 * 业务错误带服务端 message。
 *
 * 这里曾经是「网络错误 → `createTransactionOffline` 落离线 + 承诺联网后自动补传」。
 * 那条路已删除：本工程没有离线队列，写了就是谎报。
 *
 * ## 不测什么
 *
 * 不测 UI 渲染（Snackbar 由 `ManualRecordScreen` 消费 `UiEvent.ShowToast`）。
 *
 * ## 框架红线
 *
 * 必须 Jupiter（`useJUnitPlatform()`）。断言依赖 `UnconfinedTestDispatcher`
 * 的立即执行，因此无需 `advanceUntilIdle()`。`uiEvent` 是 `Channel.receiveAsFlow()`，
 * 所以要先订阅再触发，否则事件会丢。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRecordViewModelTest {

    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val templateRepository: TemplateRepository = mockk(relaxed = true)
    private val aiRepository: AiRepository = mockk(relaxed = true)
    private val streakTracker: StreakTracker = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        every { categoryRepository.observeCategories(any()) } returns
            flowOf(listOf(Category(id = 7, name = "餐饮", type = TransactionType.EXPENSE, icon = "🍜")))
        every { accountRepository.observeAccounts() } returns flowOf(emptyList())
        coEvery { transactionRepository.getTags() } returns Result.Success(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ManualRecordViewModel {
        // 空路由参数 = 不是从模板跳过来的（`templateId` 取默认值 null）
        val handle = SavedStateHandle(emptyMap<String, Any?>())
        return ManualRecordViewModel(
            transactionRepository = transactionRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            templateRepository = templateRepository,
            aiRepository = aiRepository,
            streakTracker = streakTracker,
            savedStateHandle = handle,
        )
    }

    /** 填好一笔可保存的账（金额 + 分类）。 */
    private fun filledViewModel(): ManualRecordViewModel = buildViewModel().apply {
        onAmountTextChanged("32")
        onDescriptionChanged("午饭")
        onCategorySelected(7)
    }

    @Test
    fun `server failure keeps the form values and reports the server message`() = runTest {
        coEvery { transactionRepository.createTransactions(any()) } returns
            Result.Error(422, "金额必须大于 0")

        val viewModel = filledViewModel()
        viewModel.uiEvent.test {
            viewModel.onSave()

            val event = awaitItem()
            assertEquals(
                "保存失败: 金额必须大于 0",
                (event as ManualRecordViewModel.UiEvent.ShowToast).message,
            )
        }

        // 表单原样留着：用户当场改一改就能重试
        val state = viewModel.uiState.value
        assertEquals("32", state.amountText)
        assertEquals(3200, state.amountFen)
        assertEquals("午饭", state.description)
        assertEquals(7, state.selectedCategoryId)
        assertEquals(false, state.isSaving, "isSaving 必须复位，否则按钮永久卡在「保存中」")
        // 失败后没有第二次写入：仓库只被调用一次（不存在「再落一次离线」）
        coVerify(exactly = 1) { transactionRepository.createTransactions(any()) }
        coVerify(exactly = 0) { streakTracker.onTransactionRecorded() }
    }

    @Test
    fun `network failure says there is no network and keeps the form`() = runTest {
        coEvery { transactionRepository.createTransactions(any()) } returns
            Result.Error(Result.ERROR_NETWORK, "网络连接失败，请检查网络")

        val viewModel = filledViewModel()
        viewModel.uiEvent.test {
            viewModel.onSave()

            val event = awaitItem()
            assertEquals(
                "没网，记不上",
                (event as ManualRecordViewModel.UiEvent.ShowToast).message,
            )
        }

        // 「没网，记不上」不是「稍后补传」：表单留着，本地不写任何东西
        assertEquals("32", viewModel.uiState.value.amountText)
        assertEquals(3200, viewModel.uiState.value.amountFen)
        assertEquals(false, viewModel.uiState.value.isSaving)
    }

    @Test
    fun `success clears the form and reports the recorded outcome`() = runTest {
        coEvery { transactionRepository.createTransactions(any()) } returns Result.Success(emptyList())

        val viewModel = filledViewModel()
        viewModel.uiEvent.test {
            viewModel.onSave()

            val event = awaitItem()
            assertEquals(
                "已记录，继续记下一笔",
                (event as ManualRecordViewModel.UiEvent.SaveSuccess).message,
            )
        }

        // 只有服务端确认（Result.Success）才清表单
        assertEquals("", viewModel.uiState.value.amountText)
        assertEquals(0, viewModel.uiState.value.amountFen)
        assertEquals("", viewModel.uiState.value.description)
        coVerify(exactly = 1) { streakTracker.onTransactionRecorded() }
    }

    @Test
    fun `missing amount never reaches the server`() = runTest {
        val viewModel = buildViewModel()

        viewModel.uiEvent.test {
            viewModel.onSave()

            val event = awaitItem()
            assertEquals(
                "请输入金额",
                (event as ManualRecordViewModel.UiEvent.ShowToast).message,
            )
        }
        coVerify(exactly = 0) { transactionRepository.createTransactions(any()) }
        assertTrue(viewModel.uiState.value.amountFen == 0)
    }
}
