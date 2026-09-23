package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.BudgetApi
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.data.remote.dto.response.BudgetListResponse
import com.aibill.android.domain.model.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BudgetRepositoryImplTest {

    private val budgetApi: BudgetApi = mockk()
    private val repo = BudgetRepositoryImpl(budgetApi)

    private fun makeDto(id: Int = 1, amount: Int = 50000) = BudgetDto(
        id = id, categoryId = 7, categoryName = "餐饮", amount = amount,
        spent = 12000, year = 2026, month = 7,
    )

    @Test
    fun `getBudgets returns mapped domain list`() = runTest {
        coEvery { budgetApi.getBudgets(2026, 7) } returns ApiResponse(
            code = 0,
            data = BudgetListResponse(items = listOf(makeDto(1, 50000), makeDto(2, 80000))),
            message = "ok",
        )

        val result = repo.getBudgets(2026, 7)

        assertTrue(result is Result.Success)
        val budgets = (result as Result.Success).data
        assertEquals(2, budgets.size)
        assertEquals(50000, budgets[0].amount)
        assertEquals(7, budgets[0].categoryId)
    }

    @Test
    fun `getBudgets returns empty list when API returns empty`() = runTest {
        coEvery { budgetApi.getBudgets(any(), any()) } returns ApiResponse(
            code = 0, data = BudgetListResponse(items = emptyList()), message = "ok",
        )

        val result = repo.getBudgets(2026, 7)

        assertTrue(result is Result.Success)
        assertEquals(0, (result as Result.Success).data.size)
    }

    @Test
    fun `deleteBudget returns Success Unit on code 0`() = runTest {
        coEvery { budgetApi.deleteBudget(1) } returns ApiResponse(
            code = 0, data = null, message = "ok",
        )

        val result = repo.deleteBudget(1)

        assertTrue(result is Result.Success)
        coVerify(exactly = 1) { budgetApi.deleteBudget(1) }
    }

    @Test
    fun `deleteBudget returns Error on non-zero code`() = runTest {
        coEvery { budgetApi.deleteBudget(1) } returns ApiResponse(
            code = 404, data = null, message = "Budget not found",
        )

        val result = repo.deleteBudget(1)

        assertTrue(result is Result.Error)
        assertEquals(404, (result as Result.Error).code)
    }
}
