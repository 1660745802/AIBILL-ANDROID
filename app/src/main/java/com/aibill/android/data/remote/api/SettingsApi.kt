package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

@JsonClass(generateAdapter = true)
data class SettingsDto(
    @Json(name = "default_account_id") val defaultAccountId: Int? = null,
    @Json(name = "theme") val theme: String? = null,
    @Json(name = "ai_model") val aiModel: String? = null,
)

interface SettingsApi {

    @GET("settings")
    suspend fun getSettings(): ApiResponse<SettingsDto>

    @PUT("settings")
    suspend fun updateSettings(@Body settings: @JvmSuppressWildcards Map<String, Any>): ApiResponse<SettingsDto>
}
