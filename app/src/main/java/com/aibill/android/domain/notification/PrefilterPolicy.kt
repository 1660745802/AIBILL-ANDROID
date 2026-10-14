package com.aibill.android.domain.notification

/**
 * 拦截点查表得到的**动作**（跨端契约 docs/API.md 附录 C §11.2）。
 *
 * 云控 `prefilter_policy` 是 `Record<rule_id, 'block' | 'forward' | 'sample'>`：
 * 客户端在每个拦截点算出 `rule_id` 后查这张表，决定「丢 / 转发交服务端判 / 丢+采样」。
 *
 * 这只是**策略**，不是判据：判据仍由客户端判据链与云控 `precheck` 决定，
 * 本枚举只回答「命中之后怎么处置」。因此改归表 = 服务端改 `rules.json`（进 `content_hash`，
 * 全体客户端重拉），**不需要发版**——这就是「一次发版引入机制、之后免发版」的落点。
 */
enum class BlockAction {

    /** 本地丢弃（缺省；旧快照 / 旧客户端 / 缺键都是它）。 */
    BLOCK,

    /**
     * **不拦**，继续走完后续判据链；整条链都没拦 → 转发 `POST /api/ai/parse`，
     * 由服务端预筛判并落 `ai_parse_filters`（含原文）。
     *
     * 原则：**正则与 AI 冲突以 AI 为准**——有证据但不确定的文本判据（日报/促销句式/
     * 营销词/账务判据/列表页）交服务端判，客户端不自己判死。
     */
    FORWARD,

    /** 本地丢弃 + 采样上报内容（`POST /api/ai/capture-report`，契约 §11.3）。 */
    SAMPLE;

    companion object {
        /**
         * 云控字面量 → 枚举。**未知/非法返回 null**，调用方按 [BLOCK] 处理（缺省语义），
         * 绝不因为归表写错而放行——写错的代价必须是「维持今天的行为」，不是「漏拦一批」。
         *
         * **大小写敏感**（与 `validateRules` 的三值逐字一致）：服务端会拒掉整份写错的规则
         * 文件，客户端这份严格只是为了让「手改/陈旧的载荷」在日志里可见，
         * 而不是静默接受一个服务端其实不认的值。
         */
        fun fromWire(value: String?): BlockAction? = when (value?.trim()) {
            "block" -> BLOCK
            "forward" -> FORWARD
            "sample" -> SAMPLE
            else -> null
        }

        /** 上云控的线格式（仅日志/测试用；客户端只消费不下发）。 */
        fun toWire(action: BlockAction): String = action.name.lowercase()
    }
}

/**
 * `prefilter_policy` 的领域视图：`rule_id → [BlockAction]`。
 *
 * **缺省 = 全部 [BlockAction.BLOCK]**（[actionFor] 查不到就返回 BLOCK）：
 * 旧快照没有这张表、云控没下发、`rule_id` 拼错——全部退化成「今天的行为」，
 * 因此引入这张表本身对线上零影响。
 *
 * 键口径与服务端**同源**（契约 §11.2）：复用 ⑤ 上报用的 `rule_id`，
 * 即 `PrefilterStats.clientOnlyKey(reason)`（`client_only.<枚举名>`）与
 * `CaptureFunnelStats.reportKey(stage)`（`client_only.capture_dropped_<ch>_<slug>`），
 * **不另起一套名字**——否则 `forward`/`sample` 永远对不上客户端实际拦截用的 id。
 */
@JvmInline
value class PrefilterPolicy(val actions: Map<String, BlockAction>) {

    /** 查表；缺键 / 未知 `rule_id` → [BlockAction.BLOCK]。 */
    fun actionFor(ruleId: String): BlockAction = actions[ruleId] ?: BlockAction.BLOCK

    val isEmpty: Boolean get() = actions.isEmpty()

    companion object {
        /** 空表（= 全 BLOCK）。旧规则载荷 / 旧快照 / 单测默认值都用它。 */
        val EMPTY = PrefilterPolicy(emptyMap())
    }
}
