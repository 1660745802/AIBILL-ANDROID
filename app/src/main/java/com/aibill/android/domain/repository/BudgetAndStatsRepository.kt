package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result

data class Budget(
    val id: Int,
    val categoryId: Int,
    val categoryName: String?,
    val amount: Int, // 分
    val spent: Int, // 分
    val year: Int,
    val month: Int,
) {
    val percent: Float get() = if (amount > 0) spent.toFloat() / amount else 0f
    val isExceeded: Boolean get() = spent > amount
}

data class StatsSummary(
    val expense: Int, // 分
    val income: Int, // 分
    val balance: Int, // 分
    val expenseChange: Int?, // 环比变化百分比，可能为 null
)

data class CategoryStat(
    val categoryId: Int,
    val categoryName: String,
    val categoryIcon: String,
    val amount: Int, // 分
    val percent: Double,
)

data class TrendPoint(
    val date: String,
    val amount: Int, // 分
)

interface BudgetRepository {
    suspend fun getBudgets(year: Int, month: Int): Result<List<Budget>>
    suspend fun createBudget(categoryId: Int, amount: Int, year: Int, month: Int): Result<Budget>
    suspend fun deleteBudget(id: Int): Result<Unit>
    /** PR #61：补 update 方法，ViewModel 之前直接调 budgetApi.updateBudget 绕过 Repository */
    suspend fun updateBudget(id: Int, amount: Int): Result<Budget>
}

interface StatsRepository {
    suspend fun getSummary(year: Int, month: Int): Result<StatsSummary>
    suspend fun getByCategory(year: Int, month: Int, type: String): Result<List<CategoryStat>>
    suspend fun getTrend(year: Int, month: Int, period: String, type: String): Result<List<TrendPoint>>
}
