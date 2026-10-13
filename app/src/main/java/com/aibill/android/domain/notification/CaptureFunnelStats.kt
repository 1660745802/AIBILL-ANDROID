package com.aibill.android.domain.notification

/**
 * **捕获层漏斗计数**：把「服务端日志看不见的那一段」变成可计数。
 *
 * 捕获层是黑盒——A11Y 白名单外的 App、没命中成功词、防抖/冷却丢弃、NLS 早退……
 * 这些记录从未到达服务端，也不进预筛。本接口在三个平台 adapter 的每个**早退分支**
 * 记一次，于是「收到多少 → 各层丢掉多少」成为可观测的漏斗。
 *
 * ## C03：类型化（替换 `record(stage: String)`）
 *
 * 档位从裸字符串提升为类型，渠道由类型携带：
 * - [recordReceived]：入口（漏斗顶）；
 * - [recordDropped]：捕获层早退，档位是 [CaptureDrop]（渠道 + slug + label 一体）；
 * - [recordAiExtraItems] / [recordUnparsedFailed] / [recordUnparsedInputInvalid]：
 *   AI 已应答之后的观测（不是捕获层早退）。
 *
 * 内部存储键仍是字符串（`received.<ch>` / `dropped.<ch>.<slug>` / `ai.<ch>.extra_items` /
 * `unparsed.<ch>.ai_failed` / `unparsed.<ch>.input_invalid`），[Snapshot.reportCountsFor] 语义不变；
 * 上报键机械派生，**没有映射表**。labels 随类型定义，不再有手工 LABELS 表。
 *
 * ## 与 [PrefilterStats] 的分工（不重复计数）
 *
 * 捕获层管「adapter 自己拦下的」；预筛管「三渠道同一套文本判据拦下的」——后者在
 * [PrefilterStats] 计数，捕获层不重复。列表页（[BlockReason.LIST_PAGE]）走预筛。
 *
 * ## 窗口
 *
 * 进程内内存计数，重启清零（[snapshot] 读当前窗口，[reset] 由上报侧取走后调用）。
 */
interface CaptureFunnelStats {

    /** 记一次入口（漏斗顶）。 */
    fun recordReceived(channel: SourceChannel)

    /** 记一次捕获层早退。[drop] 自带渠道与 label。 */
    fun recordDropped(drop: CaptureDrop)

    /** 记一次「一次 AI 响应里除被采用的那一笔外还有多余条目」。 */
    fun recordAiExtraItems(channel: SourceChannel)

    /** 记一次「AI 不可用（`outcome=unavailable`）→ 丢弃」——失败率分子。 */
    fun recordUnparsedFailed(channel: SourceChannel)

    /** 记一次「入参被服务端拒（HTTP 400 空/超长）→ 丢弃」。 */
    fun recordUnparsedInputInvalid(channel: SourceChannel)

    /** 当前窗口的计数快照（不清零）。诊断页 / 日志导出用。 */
    fun snapshot(): Snapshot

    /** 清零（上报取走数据后调用，或诊断用）。 */
    fun reset()

    data class Snapshot(
        /** 各渠道入口收到的总数（来自 `received.*`）。 */
        val received: Map<SourceChannel, Long>,
        /** 各 stage 计数（不含 `received.*`）：`dropped.*` / `ai.*` / `unparsed.*`。 */
        val stages: Map<String, Long>,
    ) {
        val receivedTotal: Long get() = received.values.sum()

        /** 捕获层早退总数（只算 `dropped.*`）。 */
        val droppedTotal: Long get() = stages
            .filterKeys { !isAiResponseStage(it) && !isUnparsedStage(it) }
            .values.sum()

        /** AI 响应侧（`ai.*`）发生次数合计。 */
        val aiResponseTotal: Long get() = stages.filterKeys { isAiResponseStage(it) }.values.sum()

        /** AI 失败丢弃（`outcome=unavailable`）次数合计——失败率分子。 */
        val unparsedAiFailedTotal: Long get() = stages
            .filterKeys { isUnparsedStage(it) && !isUnparsedInputInvalidStage(it) }
            .values.sum()

        /** 入参被拒（HTTP 400）而丢弃的次数——不计入失败率。 */
        val unparsedInputInvalidTotal: Long get() = stages
            .filterKeys { isUnparsedInputInvalidStage(it) }
            .values.sum()

        /** 解析侧（AI 失败 + 入参非法）丢弃合计。 */
        val unparsedTotal: Long get() = unparsedAiFailedTotal + unparsedInputInvalidTotal

        /** 某渠道的计数，键已映射为上报键（一般为 `client_only.capture_*`）。 */
        fun reportCountsFor(channel: SourceChannel): Map<String, Long> = buildMap {
            received[channel]?.takeIf { it > 0L }?.let { put(reportKey(receivedKey(channel)), it) }
            stages.forEach { (stage, count) ->
                if (channelOf(stage) == channel) put(reportKey(stage), count)
            }
        }
    }

    companion object {
        const val RECEIVED_PREFIX = "received."
        const val DROPPED_PREFIX = "dropped."
        const val REPORT_KEY_PREFIX = "capture_"
        const val AI_RESPONSE_PREFIX = "ai."
        const val UNPARSED_PREFIX = "unparsed."

        const val AI_EXTRA_ITEMS_REASON = "extra_items"
        const val UNPARSED_AI_FAILED_REASON = "ai_failed"
        const val UNPARSED_INPUT_INVALID_REASON = "input_invalid"

        const val AI_EXTRA_ITEMS_REPORT_KEY = "client_only.ai_extra_items"
        const val UNPARSED_AI_FAILED_REPORT_KEY = "client_only.unparsed_ai_failed"
        const val UNPARSED_INPUT_INVALID_REPORT_KEY = "client_only.unparsed_input_invalid"

        /** `received.<channel>`。 */
        fun receivedKey(channel: SourceChannel): String = "$RECEIVED_PREFIX${channel.logTag.lowercase()}"

        /** `dropped.<channel>.<slug>`（渠道与 slug 由 [CaptureDrop] 携带）。 */
        fun droppedStage(drop: CaptureDrop): String =
            "$DROPPED_PREFIX${drop.channel.logTag.lowercase()}.${drop.slug}"

        /** `ai.<channel>.extra_items`。 */
        fun aiExtraItemsStage(channel: SourceChannel): String =
            "$AI_RESPONSE_PREFIX${channel.logTag.lowercase()}.$AI_EXTRA_ITEMS_REASON"

        fun isAiResponseStage(stage: String): Boolean = stage.startsWith(AI_RESPONSE_PREFIX)

        /** `unparsed.<channel>.ai_failed`。 */
        fun unparsedFailedStage(channel: SourceChannel): String =
            "$UNPARSED_PREFIX${channel.logTag.lowercase()}.$UNPARSED_AI_FAILED_REASON"

        fun isUnparsedStage(stage: String): Boolean = stage.startsWith(UNPARSED_PREFIX)

        /** `unparsed.<channel>.input_invalid`。 */
        fun unparsedInputInvalidStage(channel: SourceChannel): String =
            "$UNPARSED_PREFIX${channel.logTag.lowercase()}.$UNPARSED_INPUT_INVALID_REASON"

        fun isUnparsedInputInvalidStage(stage: String): Boolean =
            stage.endsWith(".$UNPARSED_INPUT_INVALID_REASON")

        /** 从 stage 的第二段解析渠道；缺渠道段返回 null（这类 key 不上报，防 400）。 */
        fun channelOf(stage: String): SourceChannel? {
            val parts = stage.split('.')
            if (parts.size < 2) return null
            val tag = parts[1]
            return SourceChannel.entries.firstOrNull { it.logTag.equals(tag, ignoreCase = true) }
        }

        /**
         * stage → 上报键。捕获侧机械拼为 `client_only.capture_<stage 点换下划线>`；
         * AI 响应侧 / 失败 / 入参非法各用固定键。纯机械拼接，服务端按前缀剥离即可对账。
         */
        fun reportKey(stage: String): String = when {
            isAiResponseStage(stage) -> AI_EXTRA_ITEMS_REPORT_KEY
            isUnparsedInputInvalidStage(stage) -> UNPARSED_INPUT_INVALID_REPORT_KEY
            isUnparsedStage(stage) -> UNPARSED_AI_FAILED_REPORT_KEY
            else -> PrefilterStats.CLIENT_ONLY_PREFIX + REPORT_KEY_PREFIX + stage.replace('.', '_')
        }

        /**
         * 渲染成可直接贴进日志导出的诊断文本。先给三渠道入口总数（漏斗顶），再按条数降序列早退档位。
         */
        fun render(snapshot: Snapshot): String = buildString {
            appendLine("--- 捕获层漏斗（本次进程 / 当前窗口）---")
            if (snapshot.receivedTotal == 0L && snapshot.stages.isEmpty()) {
                appendLine("（暂无捕获记录）")
                return@buildString
            }
            appendLine(
                "入口: " + SourceChannel.entries.joinToString(" · ") { channel ->
                    "${channel.logTag} 收到 ${snapshot.received[channel] ?: 0L}"
                } + " · 合计 ${snapshot.receivedTotal}"
            )
            snapshot.stages
                .filterKeys { !isAiResponseStage(it) && !isUnparsedStage(it) }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .forEach { (stage, count) -> appendLine("  $count  $stage  ${labelOf(stage)}") }
            val unparsedStages = snapshot.stages.filterKeys { isUnparsedStage(it) }
            if (unparsedStages.isNotEmpty()) {
                appendLine("解析侧丢弃（AI 失败 = 不确定；入参非法 = 请求问题；均不进用户视野）:")
                unparsedStages.entries.sortedByDescending { it.value }.forEach { (stage, count) ->
                    appendLine("  $count  $stage  ${labelOf(stage)}")
                }
            }
            val aiStages = snapshot.stages.filterKeys { isAiResponseStage(it) }
            if (aiStages.isNotEmpty()) {
                appendLine("AI 响应侧（已应答，仅供对账）:")
                aiStages.entries.sortedByDescending { it.value }.forEach { (stage, count) ->
                    appendLine("  $count  $stage  ${labelOf(stage)}")
                }
            }
            append("合计: 收到 ${snapshot.receivedTotal} · 早退丢弃 ${snapshot.droppedTotal}")
            if (snapshot.unparsedAiFailedTotal > 0L) append(" · AI 失败丢弃 ${snapshot.unparsedAiFailedTotal}")
            if (snapshot.unparsedInputInvalidTotal > 0L) append(" · 入参非法丢弃 ${snapshot.unparsedInputInvalidTotal}")
            if (snapshot.aiResponseTotal > 0L) append(" · AI 响应裁剪 ${snapshot.aiResponseTotal} 次")
            appendLine()
        }

        /** 人读标签：档位来自 [CaptureDrop] 类型，三族固定标签来自本处常量。 */
        private fun labelOf(stage: String): String = when {
            isAiResponseStage(stage) -> "AI 响应裁剪多余条目（按次数）"
            isUnparsedInputInvalidStage(stage) -> "入参被拒（HTTP 400 空/超长），丢弃"
            isUnparsedStage(stage) -> "AI 不可用（outcome=unavailable），丢弃（不进待审）"
            else -> CaptureDrop.entries.firstOrNull { droppedStage(it) == stage }?.label ?: ""
        }
    }
}
