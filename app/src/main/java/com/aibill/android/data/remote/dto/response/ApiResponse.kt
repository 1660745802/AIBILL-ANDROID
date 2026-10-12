package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 后端统一响应格式
 *
 * `parse_log_id` 是**顶层**可选字段：`/api/ai/parse` 的「模型空结果」形态（code=5001）
 * 会把日志 id 放在顶层（见跨端契约 §1 第 5 行）；其余形态放在 `data.parse_log_id`。
 * 它是反馈闭环（`/api/ai/parse-feedback`）的唯一钥匙 —— 旧 DTO 没这个字段，
 * Moshi 静默丢掉，于是「用户纠正 → 服务端学习」这条闭环一直断在这里。
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "code") val code: Int,
    @Json(name = "data") val data: T?,
    @Json(name = "message") val message: String,
    @Json(name = "parse_log_id") val parseLogId: Long? = null
)

/**
 * 分页响应
 */
@JsonClass(generateAdapter = true)
data class PaginatedResponse<T>(
    @Json(name = "items") val items: List<T>,
    @Json(name = "total") val total: Int,
    @Json(name = "page") val page: Int,
    @Json(name = "page_size") val pageSize: Int
) {
    val hasMore: Boolean get() = (page * pageSize) < total
}
