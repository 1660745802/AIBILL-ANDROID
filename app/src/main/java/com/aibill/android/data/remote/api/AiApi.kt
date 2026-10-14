package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.request.AiParseRequest
import com.aibill.android.data.remote.dto.request.CaptureReportRequest
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CaptureReportResponseDto
import com.aibill.android.data.remote.dto.response.FilterReportResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApi {

    @POST("ai/parse")
    suspend fun parse(@Body request: AiParseRequest): ApiResponse<AiParseResponseDto>

    /**
     * 预筛拦截计数**按窗口聚合**上报（跨端契约 §4）。
     *
     * 调用方必须当成 best-effort：失败不上抛、不阻塞（失败窗口由下次上报原窗口重发，
     * 服务端 upsert 幂等）。`client_only.*` 服务端无对应判据，不参与误杀率比较。
     */
    @POST("ai/filter-report")
    suspend fun filterReport(@Body request: FilterReportRequest): ApiResponse<FilterReportResponseDto>

    /**
     * 被拦内容采样上报（跨端契约 §11.3）。
     *
     * 与 [filterReport] 并列但分开：那条是聚合计数，这条是样本内容（stored 分开）。
     * 调用方必须当成 best-effort：失败不影响用户操作，原窗口重发（服务端
     * `UNIQUE(user_id, content_hash, blocked_by)` 幂等）。
     */
    @POST("ai/capture-report")
    suspend fun captureReport(@Body request: CaptureReportRequest): ApiResponse<CaptureReportResponseDto>
}
