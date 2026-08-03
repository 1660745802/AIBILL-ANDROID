package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.NotificationRulesResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface NotificationRulesApi {
    @GET("config/notification-rules")
    suspend fun getRules(
        @Header("If-None-Match") etag: String? = null
    ): Response<NotificationRulesResponse>
}
