package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.BudgetApi
import com.aibill.android.data.remote.api.StatsApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.Budget
import com.aibill.android.domain.repository.BudgetRepository
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TrendPoint
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
        return safeApiCall { budgetApi.deleteBudget(id) }
    }
}

@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val statsApi: StatsApi,
) : StatsRepository {

    override suspend fun getSummary(year: Int, month: Int): Result<StatsSummary> {
        return safeApiCall { statsApi.getSummary(year, month) }.map { dto ->
            StatsSummary(dto.expense, dto.income, dto.balance, dto.expenseChange)
        }
    }

    override suspend fun getByCategory(year: Int, month: Int, type: String): Result<List<CategoryStat>> {
        return safeApiCall { statsApi.getByCategory(year, month, type) }.map { response ->
            response.items.map { dto ->
                CategoryStat(dto.categoryId, dto.categoryName, dto.categoryIcon, dto.amount, dto.percent)
            }
        }
    }

    override suspend fun getTrend(year: Int, month: Int, period: String, type: String): Result<List<TrendPoint>> {
        return safeApiCall { statsApi.getTrend(year, month, period, type) }.map { response ->
            response.items.map { dto -> TrendPoint(dto.date ?: dto.month ?: "", dto.amount) }
        }
    }
}
