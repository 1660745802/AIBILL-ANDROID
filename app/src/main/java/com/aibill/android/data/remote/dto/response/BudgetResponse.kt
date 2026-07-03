package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BudgetDto(
    @Json(name = "id") val id: Int,
    @Json(name = "category_id") val categoryId: Int,
    @Json(name = "category_name") val categoryName: String? = null,
    @Json(name = "amount") val amount: Int, // 分
    @Json(name = "spent") val spent: Int = 0, // 分
    @Json(name = "year") val year: Int,
    @Json(name = "month") val month: Int
)

/**
 * 后端 GET /api/budgets 实际返回 { items: [...], year, month }
 */
@JsonClass(generateAdapter = true)
data class BudgetListResponse(
    @Json(name = "items") val items: List<BudgetDto> = emptyList(),
    @Json(name = "year") val year: Int? = null,
    @Json(name = "month") val month: Int? = null
)
