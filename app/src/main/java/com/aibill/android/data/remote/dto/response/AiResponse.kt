package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiParseResponseDto(
    @Json(name = "items") val items: List<AiParsedItemDto>,
    @Json(name = "raw_input") val rawInput: String
)

@JsonClass(generateAdapter = true)
data class AiParsedItemDto(
    @Json(name = "type") val type: String,
    @Json(name = "amount") val amount: Int,
    @Json(name = "category_id") val categoryId: Int,
    @Json(name = "category_name") val categoryName: String,
    @Json(name = "category_icon") val categoryIcon: String,
    @Json(name = "description") val description: String?,
    @Json(name = "date") val date: String,
    @Json(name = "account_id") val accountId: Int?,
    @Json(name = "account_name") val accountName: String?,
    @Json(name = "target_account_id") val targetAccountId: Int?,
    @Json(name = "target_account_name") val targetAccountName: String?
)
