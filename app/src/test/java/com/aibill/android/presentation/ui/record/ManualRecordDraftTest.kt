package com.aibill.android.presentation.ui.record

import app.cash.turbine.test
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualRecordDraftTest {
    private val transactions = mockk<TransactionRepository>()
    private val categories = MutableStateFlow(
        listOf(Category(id = 7, name = "餐饮", type = TransactionType.EXPENSE, icon = "food")),
    )
    private val requests = mutableListOf<Transaction>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { transactions.getTags() } returns Result.Success(emptyList())
        coEvery { transactions.createTransactions(any()) } coAnswers {
            requests += firstArg<List<Transaction>>().single()
            Result.Error(Result.ERROR_NETWORK, "response lost")
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun filledViewModel(): ManualRecordViewModel {
        val categoryRepository = mockk<CategoryRepository> {
            every { observeCategories(any()) } returns categories
        }
        val accounts = mockk<AccountRepository> {
            every { observeAccounts() } returns flowOf(emptyList())
        }
        return ManualRecordViewModel(
            transactions, categoryRepository, accounts, mockk(relaxed = true), mockk(relaxed = true),
        ).apply {
            onAmountTextChanged("32")
            onCategorySelected(7)
            onDescriptionChanged("午饭")
        }
    }

    @Test
    fun `invalid current text cannot save a previous valid amount`() = runTest {
        listOf("32+", "32/0", "99999999999999999999", "1000000.01", "abc", "-1", "0").forEach { text ->
            val vm = filledViewModel()
            vm.onAmountTextChanged(text)
            vm.uiEvent.test {
                vm.onSave()
                runCurrent()
                val event = awaitItem() as ManualRecordViewModel.UiEvent.ShowToast
                assertTrue(event.message.contains("金额"), text)
                assertEquals(text, vm.uiState.value.amountText)
                assertFalse(vm.uiState.value.isSaving)
                coVerify(exactly = 0) { transactions.createTransactions(any()) }
            }
        }
    }

    @Test
    fun `incomplete keypad expression stays editable and repaired text is parsed at save`() = runTest {
        val vm = filledViewModel()
        vm.onAmountInput("+")
        assertEquals("32+", vm.uiState.value.amountText)
        vm.uiEvent.test {
            vm.onSave()
            runCurrent()
            assertTrue(awaitItem() is ManualRecordViewModel.UiEvent.ShowToast)
            coVerify(exactly = 0) { transactions.createTransactions(any()) }
            vm.onAmountInput("8")
            vm.onSave()
            runCurrent()
            assertTrue(awaitItem() is ManualRecordViewModel.UiEvent.ShowToast)
            assertEquals(4000, requests.single().amount)
        }
    }

    @Test
    fun `deleted expression is revalidated and maximum amount is accepted`() = runTest {
        val vm = filledViewModel()
        vm.onAmountTextChanged("32+8")
        vm.onAmountDelete()
        vm.onSave()
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.onAmountTextChanged("1000000")
        vm.onSave()
        runCurrent()
        assertEquals(100_000_000, requests.single().amount)
    }

    @Test
    fun `lost response retry sends same request and only one server record exists`() = runTest {
        val serverRecords = mutableSetOf<String>()
        coEvery { transactions.createTransactions(any()) } coAnswers {
            val transaction = firstArg<List<Transaction>>().single()
            requests += transaction
            if (serverRecords.add(transaction.clientId)) {
                Result.Error(Result.ERROR_NETWORK, "committed but response lost")
            } else {
                Result.Success(listOf(transaction)) // 服务端 duplicates 回执经仓库核验后的结果。
            }
        }
        val vm = filledViewModel()
        runCurrent()
        vm.onSave()
        runCurrent()
        assertEquals("32", vm.uiState.value.amountText)
        vm.onSave()
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1], "重试不仅复用 clientId，也复用时间和完整载荷")
        assertEquals(1, serverRecords.size)
        assertEquals("", vm.uiState.value.amountText)

        vm.onAmountTextChanged("32")
        vm.onCategorySelected(7)
        vm.onSave()
        runCurrent()
        assertNotEquals(requests[0].clientId, requests[2].clientId)
    }

    @Test
    fun `double click is blocked before coroutine starts and throughout suspended request`() = runTest {
        val response = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { transactions.createTransactions(any()) } coAnswers { response.await() }
        val vm = filledViewModel()
        vm.onSave()
        assertTrue(vm.uiState.value.isSaving)
        vm.onSave()
        runCurrent()
        vm.onSave()
        coVerify(exactly = 1) { transactions.createTransactions(any()) }
        response.complete(Result.Error(Result.ERROR_NETWORK, "lost"))
        runCurrent()
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `each actual form edit creates a new id including edits reverted before saving`() = runTest {
        val edits: List<(ManualRecordViewModel) -> Unit> = listOf(
            { it.onAmountTextChanged("33") },
            { it.onDescriptionChanged("晚饭") },
            { it.onCategorySelected(8) },
            { it.onAccountSelected(2) },
            { it.onTargetAccountSelected(3) },
            { it.onDateChanged("2026-10-01") },
            { it.onTagAdded("新标签") },
            { it.onTagRemoved("原标签") },
            { it.onTypeChanged("transfer") },
            { it.onAmountInput("+"); it.onAmountDelete() },
        )
        edits.forEach { edit ->
            val vm = filledViewModel().apply { onTagAdded("原标签") }
            vm.onSave()
            runCurrent()
            val original = requests.last()
            edit(vm)
            vm.onSave()
            runCurrent()
            assertNotEquals(original.clientId, requests.last().clientId)
        }
    }

    @Test
    fun `no-op callbacks and refreshed category metadata do not rotate pending id`() = runTest {
        val vm = filledViewModel()
        runCurrent()
        vm.onSave()
        runCurrent()
        vm.onAmountTextChanged("32")
        vm.onDescriptionChanged("午饭")
        vm.onCategorySelected(7)
        vm.onTypeChanged("expense")
        vm.onAiInputChanged("尚未用于填充的文本")
        categories.value = categories.value.map { it.copy(name = "饮食") }
        runCurrent()
        vm.onSave()
        runCurrent()
        assertEquals(requests[0], requests[1])
    }

    @Test
    fun `old successful response cannot reset a draft edited during save`() = runTest {
        val response = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { transactions.createTransactions(any()) } coAnswers {
            requests += firstArg<List<Transaction>>().single()
            response.await()
        }
        val vm = filledViewModel()
        vm.onSave()
        runCurrent()
        vm.onDescriptionChanged("下一笔")
        response.complete(Result.Success(emptyList()))
        runCurrent()
        assertEquals("下一笔", vm.uiState.value.description)
        vm.onSave()
        runCurrent()
        assertNotEquals(requests[0].clientId, requests[1].clientId)
    }
}
