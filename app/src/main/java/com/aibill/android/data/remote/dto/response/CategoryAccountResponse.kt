package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CategoryDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "type") val type: String,
    @Json(name = "icon") val icon: String,
    @Json(name = "sort_order") val sortOrder: Int
)

@JsonClass(generateAdapter = true)
data class AccountDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "type") val type: String,
    @Json(name = "icon") val icon: String,
    @Json(name = "current_balance") val currentBalance: Int
)

/**
 * 后端 GET /api/categories 实际返回 { items: [...] }
 */
@JsonClass(generateAdapter = true)
data class CategoryListResponse(
    @Json(name = "items") val items: List<CategoryDto> = emptyList()
)

/**
 * 后端 GET /api/accounts 实际返回 { items: [...] }
 */
@JsonClass(generateAdapter = true)
data class AccountListResponse(
    @Json(name = "items") val items: List<AccountDto> = emptyList()
)
