package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.request.ParseFeedbackRequest
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.FilterReportResponseDto
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

    /**
     * 预筛拦截计数**按窗口聚合**上报（跨端契约 §4）。
     *
     * 调用方必须当成 best-effort：失败不上抛、不阻塞（失败窗口由下次上报原窗口重发，
     * 服务端 upsert 幂等）。`client_only.*` 服务端无对应判据，不参与误杀率比较。
     */
    @POST("ai/filter-report")
    suspend fun filterReport(@Body request: FilterReportRequest): ApiResponse<FilterReportResponseDto>
}
