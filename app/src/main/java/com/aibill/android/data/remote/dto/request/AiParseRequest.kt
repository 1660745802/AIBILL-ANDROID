package com.aibill.android.data.remote.dto.request

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `POST /api/ai/parse` 请求体（跨端契约 §5）。
 *
 * `input` 仍是**唯一必填**：其余字段全部可选，旧服务端会忽略它们（zod 剥离），
 * 行为与只发 `input` 时一致。
 *
 * @param receivedAt **必须带时区偏移**（如 `2026-10-12T23:40:00+08:00`），否则服务端 400。
 *   它的作用是让服务端用**通知到达时刻**的北京日期当「今天」：
 *   通知 23:40 到、服务端 00:05 才解析时，这笔应落在**通知当天**而不是明天。
 *   越出 [今天-7, 今天+1] 会被服务端忽略并告警（手机时钟护栏）。
 * @param source 来源应用；`package_name` 必发（服务端的 `source_mapping` 是友好名唯一真源）
 */
@JsonClass(generateAdapter = true)
data class AiParseRequest(
    @Json(name = "input") val input: String,
    @Json(name = "channel") val channel: String? = null,
    @Json(name = "source") val source: ParseSourceDto? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "received_at") val receivedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class ParseSourceDto(
    @Json(name = "package_name") val packageName: String,
    /** 仅供参考：友好名以服务端 `source_mapping` 为准，客户端不参与维护 */
    @Json(name = "source_name") val sourceName: String? = null,
)
