package com.aibill.android.domain.notification

import java.util.Locale

/**
 * 预筛拦截计数。
 *
 * ## 为什么需要它（PRODUCT §三 的第一缺口）
 *
 * 「降 AI 调用」这件事没有度量就是瞎砍：预筛每拦一条都是一次「可能漏账」的赌注，
 * 今天拦了什么、拦了多少条**没有任何计数**，所以没人敢收紧判据，也没证据证明
 * 收紧之后有没有误杀。服务端早有对应的做法（`ai_parse_filters` 表，实测拦下 53%）。
 *
 * ## 键的命名空间（跨端契约 §3 / §4）
 *
 * - 客户端**自有**判据的拦截：`client_only.<BlockReason>` —— 服务端**无对应判据**，
 *   只做「客户端拦了多少」的量级观察，**不参与误杀率比较**；
 * - 云控 `precheck` 判据的拦截：直接用它下发的 `rule_id`（`precheck.<reason>`），
 *   与 `ai_parse_filters` 同口径，⑤ 对账是**机械前缀剥离**而不是映射表；
 * - `precheck_unknown_expr`：契约漂移诊断（未知 expr 形态 / 缺 flags / 引用缺失 signal），
 *   **不是拦截**，不计入拦截率。
 *
 * ## 窗口
 *
 * [drain] 返回「上一窗口」的计数并清零，窗口起点从上次清零（或构造）时刻起算——
 * 供 `PrefilterReportImpl` 按窗口上报。进程内内存计数，重启清零（服务的是「最近一段
 * 时间的分布」，不是累计账）。
 */
interface PrefilterStats {

    /** 记一次拦截。[reason] 见类 KDoc 的命名空间约定（`client_only.*` / `precheck.*`）。 */
    fun recordBlocked(channel: SourceChannel, reason: String)

    /** 记一次放行（交给下游解析）。 */
    fun recordPassed(channel: SourceChannel)

    /** 记一次「云控 precheck 有规则不可用、已忽略」诊断，键固定为 `precheck_unknown_expr`。 */
    fun recordUnusablePrecheckRule()

    /**
     * 记一次「服务端回执里有它不认识的 `precheck.*`」诊断（双向漂移信号），
     * 键固定为 `precheck_unknown_ids`——**纯本地**，不是上报的 `rule_id`。
     */
    fun recordUnknownPrecheckIds()

    /** 当前窗口的计数快照（不清零）。诊断页用。 */
    fun snapshot(): Snapshot

    /** 上报用：返回当前窗口快照并清零，下一窗口从 [now] 起算。 */
    fun drain(now: Long): Snapshot

    /** 清零（诊断用）。 */
    fun reset()

    data class Snapshot(
        val windowStart: Long,
        val windowEnd: Long,
        val channels: Map<SourceChannel, ChannelCounts>,
    ) {
        data class ChannelCounts(
            val blocked: Map<String, Long>,
            /** 诊断：云控规则不可用（`precheck_unknown_expr`），不算拦截。 */
            val unusableRules: Map<String, Long>,
            val passed: Long,
        ) {
            val blockedTotal: Long get() = blocked.values.sum()
            val total: Long get() = blockedTotal + passed

            /** 拦截率（0~100）。总数为 0 时返回 0。 */
            val blockRatePercent: Double
                get() = if (total == 0L) 0.0 else blockedTotal * 100.0 / total
        }

        val blockedTotal: Long get() = channels.values.sumOf { it.blockedTotal }
        val passed: Long get() = channels.values.sumOf { it.passed }
        val total: Long get() = blockedTotal + passed

        val blockRatePercent: Double
            get() = if (total == 0L) 0.0 else blockedTotal * 100.0 / total
    }

    companion object {
        /** 客户端自有判据的键前缀（契约 §4：服务端无对应，不参与误杀率比较）。 */
        const val CLIENT_ONLY_PREFIX = "client_only."

        /** 契约漂移诊断键（契约 §3：未知 expr 形态 → 忽略该条 + 计数）。 */
        const val PRECHECK_UNKNOWN_EXPR = "precheck_unknown_expr"

        /**
         * 双向漂移诊断键：上报的 `precheck.*` 不在服务端当前清单里（回执 `unknown_precheck_ids`）。
         *
         * ⚠️ `precheck_unknown_*` 是**本地诊断**，不满足服务端 `rule_id` 命名空间
         * （`precheck.*` / `client_only.*`），**不得**进入 `/api/ai/filter-report` 的 `counts`。
         */
        const val PRECHECK_UNKNOWN_IDS = "precheck_unknown_ids"

        /** `BlockReason` → 上报键（纯机械拼接，不含映射表）。 */
        fun clientOnlyKey(reason: BlockReason): String = "$CLIENT_ONLY_PREFIX${reason.name}"

        /**
         * 渲染成可直接贴进日志导出的诊断文本。
         *
         * 放在 domain 的伴生对象（而不是 data 的实现里）：presentation 只需要依赖这个接口
         * 就能拿到可读文本，不必反向依赖 data 层实现。按渠道分组、组内按条数降序。
         */
        fun render(snapshot: Snapshot): String = buildString {
            appendLine("--- 预筛拦截分布（本次进程 / 当前窗口）---")
            if (snapshot.channels.isEmpty()) {
                appendLine("（暂无拦截记录）")
                return@buildString
            }
            snapshot.channels.entries
                .sortedBy { it.key.ordinal }
                .forEach { (channel, counts) ->
                    appendLine(
                        "${channel.logTag}: 放行 ${counts.passed} · 拦截 ${counts.blockedTotal} · " +
                            "拦截率 ${percent(counts.blockRatePercent)}%"
                    )
                    counts.blocked.entries.sortedByDescending { it.value }.forEach { (key, count) ->
                        appendLine("  $count  $key  ${labelOf(key)}")
                    }
                    counts.unusableRules.entries.sortedByDescending { it.value }.forEach { (key, count) ->
                        appendLine("  $count  $key  （云控规则不可用，已忽略）")
                    }
                }
            appendLine("合计: 放行 ${snapshot.passed} · 拦截 ${snapshot.blockedTotal} · 拦截率 ${percent(snapshot.blockRatePercent)}%")
        }

        private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        /**
         * 键 → 人读标签。`precheck.*` 是云控判据（旧实现用 `removePrefix("client_only.")` 查表，
         * 永远查不到 → 该标签从未生效；C03 修掉）；其余按 [BlockReason] **穷尽 `when`**，
         * 新增枚举值漏标签会编译失败，不再靠手工表。
         */
        private fun labelOf(key: String): String {
            if (key.startsWith("precheck.")) return "云控 precheck 判据"
            val reason = BlockReason.entries.firstOrNull { clientOnlyKey(it) == key } ?: return ""
            return blockLabel(reason)
        }

        private fun blockLabel(reason: BlockReason): String = when (reason) {
            BlockReason.SELF_NOTIFICATION -> "自身通知"
            BlockReason.DUPLICATE_CONTENT -> "内存去重(5s)"
            BlockReason.BLANK_TEXT -> "空正文"
            BlockReason.SYSTEM_GROUP_SUMMARY -> "系统分组摘要"
            BlockReason.SUMMARY_REPORT -> "日报/汇总"
            BlockReason.PROMO_SENTENCE -> "促销句式"
            BlockReason.MARKETING -> "营销词"
            BlockReason.NOT_FINANCIAL -> "非账务判据"
            BlockReason.LIST_PAGE -> "账单/明细列表页"
            BlockReason.AI_MEMORY -> "长窗记忆(5min)"
            BlockReason.AI_MUTED -> "AI 熔断静音"
            BlockReason.DB_DUPLICATE -> "DB 去重(1s)"
            BlockReason.PRECHECK -> "云控 precheck 判据"
        }
    }
}
