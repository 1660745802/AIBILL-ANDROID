package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result

data class StatsSummary(
    val expense: Int, // 分
    val income: Int, // 分
    val balance: Int, // 分
    val expenseChange: Int?, // 环比变化百分比，可能为 null
    val incomeChange: Int? = null, // PR #55 补 income 环比
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

interface StatsRepository {
    suspend fun getSummary(year: Int, month: Int): Result<StatsSummary>
    suspend fun getByCategory(year: Int, month: Int, type: String): Result<List<CategoryStat>>
    suspend fun getTrend(year: Int, month: Int, period: String, type: String): Result<List<TrendPoint>>
}