package com.aibill.android.data.remote.dto.request

import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `POST /api/ai/parse-feedback` 请求体（跨端契约 §2）。
 *
 * @param parseLogId 要反馈的那次解析（`ai_parse_logs.id`）
 * @param finalItems 用户确认后的**最终态**；删除整笔 = 空数组
 * @param modified 与 AI 原始结果是否不同（服务端只信这个布尔，不自行 diff）
 * @param channel 入口归因（nls/sms/a11y/manual），便于服务端按渠道归因
 */
@JsonClass(generateAdapter = true)
data class ParseFeedbackRequest(
    @Json(name = "parse_log_id") val parseLogId: Long,
    @Json(name = "final_items") val finalItems: List<AiParsedItemDto>,
    @Json(name = "modified") val modified: Boolean,
    @Json(name = "channel") val channel: String? = null,
)
