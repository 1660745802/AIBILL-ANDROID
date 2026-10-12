package com.aibill.android.presentation.ui.transactions

import app.cash.turbine.test
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 锁住两个 P0/P1 bug 的回归测试：
 * - 删除成功后 Pager 不刷新 → fix: emit ShowDeleteUndo + RefreshList
 * - 撤销永远无效 → fix: onDeleteTransaction 保存被删 Transaction，undoDelete 真正调用 restoreTransaction
 *
 * 用 Turbine 的 flow.test { } 消费事件流。`uiEvent` 是 Channel.BUFFERED +
 * receiveAsFlow（一次性事件不丢），因此**删除产生的事件必须在同一个 test 块内消费掉**，
 * 否则它会留在缓冲区里污染下一个用例的 expectNoEvents()。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: TransactionsViewModel

    private val sampleTx = Transaction(
        id = 42,
        clientId = "client-uuid",
        type = TransactionType.EXPENSE,
        amount = 5000,  // 分
        categoryId = 1,
        categoryName = "餐饮",
        categoryIcon = "🍜",
        accountId = 1,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
        description = "午饭",
        date = "2026-09-15",
        time = "12:30",
        tags = emptyList(),
        source = TransactionSource.MANUAL,
        createdAt = "2026-09-15T12:30:00Z",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = mockk()
        categoryRepository = mockk()
        // init {} 里 loadAvailableTags() / loadCategories() 会调 mock 的方法
        coEvery { transactionRepository.getTags() } returns Result.Success(emptyList())
        coEvery { categoryRepository.getCategoriesOnce() } returns Result.Success(emptyList())
        viewModel = TransactionsViewModel(
            transactionRepository = transactionRepository,
            categoryRepository = categoryRepository,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delete success emits ShowDeleteUndo and RefreshList in order`() = runTest {
        coEvery { transactionRepository.deleteTransaction(42) } returns Result.Success(Unit)

        viewModel.uiEvent.test {
            viewModel.onDeleteTransaction(sampleTx)
            advanceUntilIdle()

            assertEquals(TransactionsViewModel.UiEvent.ShowDeleteUndo, awaitItem())
            assertEquals(TransactionsViewModel.UiEvent.RefreshList, awaitItem())
            expectNoEvents() // 断言无多余事件（Turbine: expectNoEvents() 本身即断言，不是 assertTrue(== null)）
        }
        coVerify(exactly = 1) { transactionRepository.deleteTransaction(42) }
    }

    @Test
    fun `delete failure emits Toast only, no RefreshList`() = runTest {
        coEvery { transactionRepository.deleteTransaction(42) } returns Result.Error(-1, "网络失败")

        viewModel.uiEvent.test {
            viewModel.onDeleteTransaction(sampleTx)
            advanceUntilIdle()

            assertEquals(
                TransactionsViewModel.UiEvent.ShowToast("删除失败: 网络失败"),
                awaitItem(),
            )
            expectNoEvents() // 断言失败时无 RefreshList
        }
    }

    @Test
    fun `delete failure clears lastDeleted so undo is no-op (regression for sticky undo bug)`() = runTest {
        coEvery { transactionRepository.deleteTransaction(42) } returns Result.Error(-1, "网络失败")

        viewModel.uiEvent.test {
            viewModel.onDeleteTransaction(sampleTx)
            advanceUntilIdle()
            assertEquals(
                TransactionsViewModel.UiEvent.ShowToast("删除失败: 网络失败"),
                awaitItem(),
            )

            // 删除失败后 lastDeletedTransaction 必须被清空——否则用户撤销一个根本没删成功的项会乱来
            viewModel.undoDelete()
            advanceUntilIdle()

            expectNoEvents()  // undoDelete 应立刻 return，不发任何事件
        }
        coVerify(exactly = 0) { transactionRepository.restoreTransaction(any<Int>()) }
    }

    @Test
    fun `undo after successful delete emits Toast and RefreshList, second undo is no-op`() = runTest {
        coEvery { transactionRepository.deleteTransaction(42) } returns Result.Success(Unit)
        coEvery { transactionRepository.restoreTransaction(42) } returns Result.Success(Unit)

        viewModel.uiEvent.test {
            // 1. 删除 —— 先把它产生的两个事件消费掉，避免污染后续断言
            viewModel.onDeleteTransaction(sampleTx)
            advanceUntilIdle()
            assertEquals(TransactionsViewModel.UiEvent.ShowDeleteUndo, awaitItem())
            assertEquals(TransactionsViewModel.UiEvent.RefreshList, awaitItem())

            // 2. 撤销 — 应真发请求并 emit 两个事件
            viewModel.undoDelete()
            advanceUntilIdle()

            assertEquals(TransactionsViewModel.UiEvent.ShowToast("已恢复"), awaitItem())
            assertEquals(TransactionsViewModel.UiEvent.RefreshList, awaitItem())

            // 3. 二次撤销 — lastDeletedTransaction 已被清空，应是 no-op（0 新事件）
            viewModel.undoDelete()
            advanceUntilIdle()
            expectNoEvents()
        }
        coVerify(exactly = 1) { transactionRepository.restoreTransaction(42) }
    }
}
