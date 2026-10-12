package com.aibill.android.domain.notification

/**
 * 预筛拦截计数。
 *
 * ## 为什么需要它（PRODUCT §三 的第一缺口）
 *
 * 「降 AI 调用」这件事没有度量就是瞎砍：预筛每拦一条都是一次「可能漏账」的赌注，
 * 今天拦了什么、拦了多少条**没有任何计数**，所以没人敢收紧判据，也没证据证明
 * 收紧之后有没有误杀。
 *
 * 服务端早有对应的做法（`ai_parse_filters` 表：tier + reasons + 判定耗时，
 * 实测拦下 53% 的纯噪声）。客户端补上同一件事的最小版本：按 [BlockReason] 计数，
 * 供日志导出查看；将来接遥测端点时直接复用这份计数。
 *
 * 纯内存、进程级；不做持久化（重启清零可接受——它服务的是「最近一段时间的分布」，
 * 而不是累计账）。
 */
interface PrefilterStats {

    /** 记一次拦截。 */
    fun recordBlocked(reason: BlockReason)

    /** 记一次放行（交给下游解析）。 */
    fun recordPassed()

    /** 当前分布快照。 */
    fun snapshot(): Snapshot

    /** 清零（诊断用）。 */
    fun reset()

    data class Snapshot(
        val blocked: Map<BlockReason, Long>,
        val passed: Long,
    ) {
        val blockedTotal: Long get() = blocked.values.sum()
        val total: Long get() = blockedTotal + passed

        /** 拦截率（0~100）。总数为 0 时返回 0。 */
        val blockRatePercent: Double
            get() = if (total == 0L) 0.0 else blockedTotal * 100.0 / total
    }

    companion object {
        /**
         * 渲染成可直接贴进日志导出的诊断文本。
         *
         * 放在 domain 的伴生对象（而不是 data 的实现里）：presentation 只需要
         * 依赖这个接口就能拿到可读文本，不必反向依赖 data 层实现。
         *
         * 按条数降序：一眼能看出「最常被拦的是什么」——这正是收紧/放松判据时
         * 唯一有用的信息（例如 DB_DUPLICATE 占绝大多数 → 说明重复投递才是主要噪声源）。
         */
        fun render(snapshot: Snapshot): String = buildString {
            appendLine("--- 预筛拦截分布（本次进程）---")
            appendLine(
                "放行 ${snapshot.passed} · 拦截 ${snapshot.blockedTotal} · " +
                    "拦截率 ${String.format(java.util.Locale.ROOT, "%.1f", snapshot.blockRatePercent)}%"
            )
            if (snapshot.blocked.isEmpty()) {
                appendLine("（暂无拦截记录）")
            } else {
                snapshot.blocked.entries
                    .sortedByDescending { it.value }
                    .forEach { (reason, count) -> appendLine("$count  $reason  ${LABELS[reason] ?: ""}") }
            }
        }

        private val LABELS: Map<BlockReason, String> = mapOf(
            BlockReason.SELF_NOTIFICATION to "自身通知",
            BlockReason.DUPLICATE_CONTENT to "内存去重(5s)",
            BlockReason.BLANK_TEXT to "空正文",
            BlockReason.SYSTEM_GROUP_SUMMARY to "系统分组摘要",
            BlockReason.SUMMARY_REPORT to "日报/汇总",
            BlockReason.PROMO_SENTENCE to "促销句式",
            BlockReason.MARKETING to "营销词",
            BlockReason.NOT_FINANCIAL to "非账务判据",
            BlockReason.AI_MEMORY to "长窗记忆(5min)",
            BlockReason.AI_MUTED to "AI 熔断静音",
            BlockReason.DB_DUPLICATE to "DB 去重(1s)",
        )
    }
}
