package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 后端统一响应格式
 *
 * `parse_log_id` 是**顶层**可选字段：`/api/ai/parse` 的「模型空结果」形态（code=5001）
 * 会把日志 id 放在顶层（见跨端契约 §1 第 5 行）；其余形态放在 `data.parse_log_id`。
 * 客户端已不再消费它（parse-feedback 下线）：字段保留仅为与服务端契约同步，
 * 且**不得删** —— 旧 DTO 缺这个字段时 Moshi 静默丢弃，是踩过的坑。
 *
 * `outcome` 也是顶层可选字段（服务端 022 新增，见契约附录 C §1/§9），**仅 `/api/ai/parse` 使用**：
 * - `not_a_bill` = AI/规则**判非账**（#1/#3/#5，正常结果，不是故障）；
 * - `unavailable` = AI **不可用或输出不可用**（#6/#7，失败）。
 * 客户端用它把「判非账」与「AI 挂了」分成两桶计数——**不要靠 HTTP 状态去猜**。
 * 其余接口不带该字段 → null（Moshi 不开 failOnUnknown，未知键静默忽略）。
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "code") val code: Int,
    @Json(name = "data") val data: T?,
    @Json(name = "message") val message: String,
    @Json(name = "parse_log_id") val parseLogId: Long? = null,
    @Json(name = "outcome") val outcome: String? = null,
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
