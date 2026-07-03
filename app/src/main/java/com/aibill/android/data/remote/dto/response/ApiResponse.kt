package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 后端统一响应格式
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "code") val code: Int,
    @Json(name = "data") val data: T?,
    @Json(name = "message") val message: String
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
