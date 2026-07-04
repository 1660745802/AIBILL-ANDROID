package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.BudgetApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.Budget
import com.aibill.android.domain.repository.BudgetRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepositoryImpl @Inject constructor(
    private val budgetApi: BudgetApi,
) : BudgetRepository {

    override suspend fun getBudgets(year: Int, month: Int): Result<List<Budget>> {
        return safeApiCall { budgetApi.getBudgets(year, month) }.map { response ->
            response.items.map { dto ->
                Budget(
                    id = dto.id, categoryId = dto.categoryId,
                    categoryName = dto.categoryName, amount = dto.amount,
                    spent = dto.spent, year = dto.year, month = dto.month,
                )
            }
        }
    }

    override suspend fun createBudget(categoryId: Int, amount: Int, year: Int, month: Int): Result<Budget> {
        return safeApiCall {
            budgetApi.createBudget(mapOf("category_id" to categoryId, "amount" to amount, "year" to year, "month" to month))
        }.map { dto ->
            Budget(dto.id, dto.categoryId, dto.categoryName, dto.amount, dto.spent, dto.year, dto.month)
        }
    }

    override suspend fun deleteBudget(id: Int): Result<Unit> {
        // PR M7 + 一次性改为 safeApiCall（PR L4），与 sibling 方法风格一致
        return safeApiCall { budgetApi.deleteBudget(id) }.let { response ->
            when (response) {
                is Result.Success -> Result.Success(Unit)
                is Result.Error -> response
                is Result.Loading -> response
            }
        }
    }

    override suspend fun updateBudget(id: Int, amount: Int): Result<Budget> {
        return safeApiCall { budgetApi.updateBudget(id, mapOf("amount" to amount)) }.map { dto ->
            Budget(dto.id, dto.categoryId, dto.categoryName, dto.amount, dto.spent, dto.year, dto.month)
        }
    }
}
