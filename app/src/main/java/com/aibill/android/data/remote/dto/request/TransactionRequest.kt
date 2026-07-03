package com.aibill.android.data.remote.dto.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateTransactionRequest(
    @Json(name = "items") val items: List<TransactionItemRequest>
)

@JsonClass(generateAdapter = true)
data class TransactionItemRequest(
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_type") val clientType: String = "app_android",
    @Json(name = "source") val source: String,
    @Json(name = "source_detail") val sourceDetail: String? = null,
    @Json(name = "type") val type: String,
    @Json(name = "amount") val amount: Int,
    @Json(name = "category_id") val categoryId: Int? = null,
    @Json(name = "account_id") val accountId: Int? = null,
    @Json(name = "target_account_id") val targetAccountId: Int? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "date") val date: String,
    @Json(name = "time") val time: String? = null,
    @Json(name = "tags") val tags: List<String>? = null,
    @Json(name = "client_created_at") val clientCreatedAt: String? = null,
    @Json(name = "ai_raw_input") val aiRawInput: String? = null
)
