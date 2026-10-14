package com.aibill.android.data.remote.dto.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `POST /api/ai/capture-report` 请求体（跨端契约 docs/API.md 附录 C §11.3）。
 *
 * 与 `filter-report` **并列但分开**：那条上报聚合计数（拦了多少），这条上报样本内容
 * （拦的是什么），服务端也分开存（`client_filter_stats` vs `client_capture_samples`）。
 *
 * 服务端硬约束（zod + 窗口归一，与 filter-report 同源）：
 * - `samples` **1..50** 条（超限 400 不截断 → 调用方按 [MAX_SAMPLES_PER_REQUEST] 拆包）；
 * - `text` ≤ 2000 字符（超了 400 → 客户端先截断）；
 * - `channel` ∈ `nls|sms|a11y`；`kind` ∈ `notification|a11y_page`；`stage` ∈ `capture|prefilter`；
 * - `window_start`/`window_end` 为时间串（无时区按**北京时间**解释）→ 复用
 *   `PrefilterReport.formatWindowTime` 的 +08:00 墙钟口径；
 * - 幂等：`UNIQUE(user_id, content_hash, blocked_by)` → 原窗口重发安全。
 *
 * 空可选字段（`tier` / `received_at` / `text` / `text_len`）由 Moshi 直接省略
 * （默认不序列化 null），正好对上服务端的 optional。
 */
@JsonClass(generateAdapter = true)
data class CaptureReportRequest(
    @Json(name = "client_version") val clientVersion: String,
    @Json(name = "window_start") val windowStart: String,
    @Json(name = "window_end") val windowEnd: String,
    @Json(name = "samples") val samples: List<CaptureSampleDto>,
) {
    companion object {
        /** 单请求样本上限（服务端 400 不截断）。 */
        const val MAX_SAMPLES_PER_REQUEST = 50

        private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 契约示例形状：`2026-10-14T12:31:00+08:00`（带偏移，服务端按该偏移解析）。 */
        private val RECEIVED_AT_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /** epoch 毫秒 → 带 +08:00 偏移的 ISO 串（设备在任何时区都发北京时间）。 */
        fun receivedAtText(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(BEIJING_ZONE).format(RECEIVED_AT_FORMATTER)
    }
}

/**
 * 一条样本（契约 §11.3）。
 *
 * `content_hash` 是客户端算的指纹，也是服务端幂等键的一半（另一半是 `blocked_by`）——
 * 同一内容被**不同规则**拦下是两条样本（一笔可能被多条规则同时解释），这是刻意的。
 */
@JsonClass(generateAdapter = true)
data class CaptureSampleDto(
    @Json(name = "channel") val channel: String,
    /** 包名；SMS 是 `sms:<号码>`（与审计记录同口径）。 */
    @Json(name = "package") val packageName: String,
    @Json(name = "kind") val kind: String,
    @Json(name = "stage") val stage: String,
    @Json(name = "blocked_by") val blockedBy: String,
    @Json(name = "tier") val tier: Int? = null,
    @Json(name = "received_at") val receivedAt: String? = null,
    /** P1 = 原文（notification 截断 / a11y 命中片段）；null = 该拦截点拿不到内容。 */
    @Json(name = "text") val text: String? = null,
    /** 原文长度（a11y 是整页长度而不是片段长度）。 */
    @Json(name = "text_len") val textLen: Int? = null,
    @Json(name = "content_hash") val contentHash: String,
    @Json(name = "redacted") val redacted: Boolean = false,
)
