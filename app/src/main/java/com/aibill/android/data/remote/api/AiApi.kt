package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApi {

    @POST("ai/parse")
    suspend fun parse(@Body request: Map<String, String>): ApiResponse<AiParseResponseDto>

    @POST("ai/chat")
    suspend fun chat(@Body request: Map<String, String>): ApiResponse<Map<String, String>>
}
