package com.aibill.android.data.remote.dto.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `POST /api/ai/filter-report` 请求体（跨端契约 §4）。
 *
 * 拦截计数**按窗口聚合**上报（不复用 `ai_parse_filters`——那是服务端逐条事件表，
 * 客户端是聚合计数 + 客户端特有维度，混进去会让服务端口径不再可比）。
 *
 * - `rule_id` 用契约 §3 的命名空间：`precheck.*` **可比** / `client_only.*` **不可比**
 *   （后者服务端无对应判据，只做量级观察）；其它前缀会被服务端 400，调用方必须过滤；
 * - `window_start`/`window_end` 是**时间串**（无时区按北京时间解释）。客户端固定发
 *   `YYYY-MM-DD HH:MM:SS`（+08:00 墙钟），**不发** epoch 毫秒 / 设备本地时区；
 * - 服务端按 `(user_id, client_version, window_start, window_end, rule_id)` upsert 保证幂等，
 *   所以客户端失败后**原窗口重报**是安全的。
 */
@JsonClass(generateAdapter = true)
data class FilterReportRequest(
    @Json(name = "window_start") val windowStart: String,
    @Json(name = "window_end") val windowEnd: String,
    @Json(name = "client_version") val clientVersion: String,
    /** 入口归因：nls / sms / a11y（每渠道一条上报）。 */
    @Json(name = "channel") val channel: String,
    /** 1..200 条；超过由调用方拆成多个请求（服务端 400 不截断）。 */
    @Json(name = "counts") val counts: List<FilterCountDto>,
    @Json(name = "totals") val totals: FilterTotalsDto,
)

@JsonClass(generateAdapter = true)
data class FilterCountDto(
    @Json(name = "rule_id") val ruleId: String,
    @Json(name = "count") val count: Long,
)

@JsonClass(generateAdapter = true)
data class FilterTotalsDto(
    @Json(name = "notifications") val notifications: Long,
    @Json(name = "blocked") val blocked: Long,
)
