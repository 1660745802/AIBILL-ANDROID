package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.request.ParseFeedbackRequest
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApi {

    @POST("ai/parse")
    suspend fun parse(@Body request: AiParseRequest): ApiResponse<AiParseResponseDto>

    /**
     * 把用户对 AI 解析结果的纠正回传（跨端契约 §2；服务端据此学习，不会注入 prompt）。
     * 调用方必须当成 best-effort：失败不影响用户操作。
     */
    @POST("ai/parse-feedback")
    suspend fun parseFeedback(@Body request: ParseFeedbackRequest): ApiResponse<Unit>
}
