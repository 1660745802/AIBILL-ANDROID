package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `POST /api/ai/filter-report` 的回执 `data`（跨端契约 §4）。
 *
 * @param accepted 服务端收下的 `counts` 条数（仅供参考）
 * @param unknownPrecheckIds 我们上报的 `precheck.*` **不在服务端当前清单**里的那些。
 *   服务端已把它们收下并标出——这是**双向漂移信号**：非空说明客户端用了旧清单
 *   （或服务端删了判据）。调用方**不得因此失败重试**，只记 warn 日志 + 计入本地诊断。
 */
@JsonClass(generateAdapter = true)
data class FilterReportResponseDto(
    @Json(name = "accepted") val accepted: Int? = null,
    @Json(name = "unknown_precheck_ids") val unknownPrecheckIds: List<String>? = null,
)
