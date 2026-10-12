package com.aibill.android.domain.notification

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 预筛拦截计数的**上报**（跨端契约 §4，客户端半段）。
 *
 * 与 [PrefilterStats] 分开：后者是进程内计数，本接口只负责「把上一个窗口的计数
 * best-effort 发出去」。分开的理由是上报要碰网络（data 层），而计数被热路径调用。
 *
 * 契约约定：
 * - **best-effort**：失败只记日志、下个窗口再报（原窗口重发，服务端 upsert 幂等），
 *   绝不上抛、不阻塞任何用户操作；
 * - 触发只挂在后台任务（`RulesSyncWorker`），**不用前台定时器**；
 * - `precheck.*` 与服务端 `ai_parse_filters` 同口径可比；`client_only.*` 明确标注
 *   「服务端无对应判据」，不参与误杀率比较。
 *
 * ## 服务端硬约束（端点已落地）
 *
 * - `counts` 1..200 条，**超限 400 不截断** → 调用方按 [MAX_COUNTS_PER_REQUEST] 拆分多次；
 * - `rule_id` 只能是 `precheck.*` / `client_only.*`（其它前缀 400）→ [isReportableRuleId] 过滤；
 * - 窗口 `window_start`/`window_end` 是**时间串**（无时区按北京时间解释）→ [formatWindowTime]
 *   固定用 +08:00 墙钟格式化，**绝不**发设备本地时区或 epoch 毫秒；
 * - 窗口跨度 ≤ [MAX_WINDOW_SPAN_DAYS] 天 → [clampWindowStart] 防御性截断；
 * - `blocked <= notifications` → 分母口径 = 本渠道「放行 + 拦截」。
 */
interface PrefilterReport {

    /** 上报截至 [now] 的窗口并清零。实现必须吞掉所有异常（除协程取消）。 */
    suspend fun report(now: Long)

    companion object {
        /** 单次请求 `counts` 上限（服务端 400 不截断）。 */
        const val MAX_COUNTS_PER_REQUEST = 200

        /** 单窗口跨度上限（天）。 */
        const val MAX_WINDOW_SPAN_DAYS = 31L

        private const val MS_PER_DAY = 24L * 60 * 60 * 1000
        private const val MAX_WINDOW_SPAN_MS = MAX_WINDOW_SPAN_DAYS * MS_PER_DAY

        /** 时区口径：服务端规定「无时区串按北京时间解释」，客户端固定按东八区发。 */
        private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 服务端接受的无时区形式：`YYYY-MM-DD HH:MM:SS`（按北京时间解释）。 */
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 契约 §4 `channel` 取值：入口归因（与 `POST /api/ai/parse` 的 channel 同口径）。 */
        fun channelName(channel: SourceChannel): String = channel.logTag.lowercase()

        /**
         * epoch 毫秒 → **北京墙钟**时间串（无时区，服务端按北京解释）。
         *
         * 固定 +08:00：设备可能在任何时区，发设备本地时区会让服务端把同一窗口
         * 解释到不同的「北京时间」，破坏幂等键。
         */
        fun formatWindowTime(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(BEIJING_ZONE).format(TIME_FORMATTER)

        /** 窗口起点不得早于「终点 - 31 天」（服务端约束；正常路径不会触发，纯防御）。 */
        fun clampWindowStart(windowStart: Long, windowEnd: Long): Long {
            val earliest = windowEnd - MAX_WINDOW_SPAN_MS
            return if (windowStart < earliest) earliest else windowStart
        }

        /**
         * 只上报服务端接受的命名空间（`precheck.*` / `client_only.*`）。
         *
         * 其余键（例如纯本地诊断 `precheck_unknown_expr`）会被服务端 400 拒绝，
         * 因此调用方必须过滤掉——**不是截断，而是明确不上报**。
         */
        fun isReportableRuleId(ruleId: String): Boolean =
            ruleId.startsWith("precheck.") || ruleId.startsWith(PrefilterStats.CLIENT_ONLY_PREFIX)
    }
}
