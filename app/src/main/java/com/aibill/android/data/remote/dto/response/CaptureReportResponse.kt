package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `POST /api/ai/capture-report` 的回执 `data`（跨端契约 §11.3）。
 *
 * @param accepted 本次真正新落库的样本数
 * @param duplicates 命中 `UNIQUE(user_id, content_hash, blocked_by)` 被忽略的样本数——
 *   客户端 best-effort 原窗口重发时会体现在这里，**不是错误**（不因此重试）。
 */
@JsonClass(generateAdapter = true)
data class CaptureReportResponseDto(
    @Json(name = "accepted") val accepted: Int? = null,
    @Json(name = "duplicates") val duplicates: Int? = null,
)
