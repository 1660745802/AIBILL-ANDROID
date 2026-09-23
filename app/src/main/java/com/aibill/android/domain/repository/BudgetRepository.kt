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

interface BudgetRepository {
    suspend fun getBudgets(year: Int, month: Int): Result<List<Budget>>
    suspend fun createBudget(categoryId: Int, amount: Int, year: Int, month: Int): Result<Budget>
    suspend fun deleteBudget(id: Int): Result<Unit>
    /** PR #61：补 update 方法，ViewModel 之前直接调 budgetApi.updateBudget 绕过 Repository */
    suspend fun updateBudget(id: Int, amount: Int): Result<Budget>
}
