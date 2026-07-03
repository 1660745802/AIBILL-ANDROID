package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StatsSummaryDto(
    @Json(name = "expense") val expense: Int,
    @Json(name = "income") val income: Int,
    @Json(name = "balance") val balance: Int,
    @Json(name = "expense_change") val expenseChange: Int? = null,
    @Json(name = "income_change") val incomeChange: Int? = null
)

@JsonClass(generateAdapter = true)
data class CategoryStatDto(
    @Json(name = "id") val categoryId: Int,
    @Json(name = "name") val categoryName: String,
    @Json(name = "icon") val categoryIcon: String,
    @Json(name = "total") val amount: Int,
    @Json(name = "count") val count: Int = 0,
    @Json(name = "percent") val percent: Double
)

@JsonClass(generateAdapter = true)
data class TrendPointDto(
    @Json(name = "date") val date: String? = null,
    @Json(name = "month") val month: String? = null,
    @Json(name = "total") val amount: Int = 0,
    @Json(name = "count") val count: Int = 0
)

/**
 * 后端 GET /api/stats/by-category 实际返回 { items: [...], total, year, month, type }
 */
@JsonClass(generateAdapter = true)
data class CategoryStatListResponse(
    @Json(name = "items") val items: List<CategoryStatDto> = emptyList()
)

/**
 * 后端 GET /api/stats/trend 实际返回 { items: [...], period, type, year, month }
 */
@JsonClass(generateAdapter = true)
data class TrendListResponse(
    @Json(name = "items") val items: List<TrendPointDto> = emptyList()
)
