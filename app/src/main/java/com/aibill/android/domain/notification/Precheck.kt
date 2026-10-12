package com.aibill.android.domain.notification

/**
 * 入站预筛判据 `precheck`（跨端契约 `recognition-flow.md` §3）。
 *
 * ## 这是什么、为什么由服务端下发
 *
 * 服务端在调模型之前用 `classifyNotification`（tier 档位链）决定「值不值得调 AI」，
 * 实测拦下约 53% 的纯噪声。客户端预筛是**另一套分类学**（自身通知 / 系统摘要 /
 * 日报汇总 / 单包熔断 / DB 去重），契约 §3 明确**不做映射表**——强行映射只会得到
 * 第三种口径。于是服务端把它的判据原样下发，客户端按**同一 `rule_id`** 执行并计数，
 * 使 ⑤ 的上报与 `ai_parse_filters` 天然同口径。
 *
 * ## 判据语义（逐字遵守，不要重新解释）
 *
 * 1. `signals` 里每个 signal 是 `{pattern, flags}` —— **必须成对编译**。
 *    缺 `flags` → 该 signal 不可用（`money.flags='gi'`，丢了它 `rmb 100` 两端判定相反）。
 * 2. `rules[]` **顺序即判据顺序**；取**第一条命中**的规则，只看它的 `tier`：
 *    `tier ∈ {1,2,3}` → 放行去调模型；`tier ∈ {0,4}` → 拦。没有 `tier:null`。
 * 3. `type` 三种：`regex`（pattern+flags）/ `predicate`（`expr`）/ `fallback`（无条件命中，排最后）。
 * 4. `bare_amount` 的长度口径 = `input.trim().length`（UTF-16 code unit，Kotlin `String.length` 同口径）。
 *    客户端在**预筛阶段**执行，但传入的是**与请求 `input` 同一个串**：预筛先用
 *    `MarketingSuffixCleaner` 做营销后缀清洗，再算长度（否则 30 字边界会与两端计数口径分叉）。
 *
 * ## 本文件的分工
 *
 * - [PrecheckSpec] 是**原始下发形态**（表达式/正则为字符串），由 `NotificationRules` 持有；
 * - [PrecheckRules] 是**编译后的一代**（`Regex`），由 `NotificationRuleSnapshot` 持有；
 * - 编译（**全或无**：任一失败即整链不可用）在 data 层 `PrecheckCompiler`，执行在本文件的 [PrecheckJudge]（纯函数）。
 */
data class PrecheckSpec(
    val schema: Int,
    val signals: Map<String, PrecheckSignalSpec>,
    val bareAmountMaxLen: Int,
    val rules: List<PrecheckRuleSpec>,
) {
    companion object {
        /** 认识的载荷版本；不认识整体忽略并降级（契约 §3「schema 不认识 → 降级」）。 */
        const val SUPPORTED_SCHEMA = 1

        /** 契约里的默认短句长度上限（服务端也会写进 `bare_amount_max_len`）。 */
        const val DEFAULT_BARE_AMOUNT_MAX_LEN = 30

        /** 无云控 precheck（旧服务端）时的空 spec：编译出 [PrecheckRules.EMPTY]，行为与今天一致。 */
        val EMPTY = PrecheckSpec(
            schema = 0,
            signals = emptyMap(),
            bareAmountMaxLen = DEFAULT_BARE_AMOUNT_MAX_LEN,
            rules = emptyList(),
        )
    }
}

/** `precheck.signals.<name>` 的原始形态。`flags` 缺失即 signal 不可用（不得当 `''` 用）。 */
data class PrecheckSignalSpec(
    val pattern: String,
    val flags: String?,
)

/** `precheck.rules[]` 的原始形态。字段按 `type` 二选一。 */
data class PrecheckRuleSpec(
    val id: String,
    val tier: Int,
    val type: String,
    val pattern: String? = null,
    val flags: String? = null,
    val expr: String? = null,
)

/**
 * 一代**已编译**的 precheck。
 *
 * [unusableRuleIds] 是「因未知 expr 形态 / 缺 flags / 正则编译失败 / 引用缺失 signal
 * 而被整体丢弃」的规则 id：一旦非空即表示**整链已停用**（`available == false` → 降级放行），
 * 这些 id 只用于诊断与上报（定位服务端载荷漂移）
 * `precheck_unknown_expr`（契约 §3 要求「忽略该条 + 记日志 + 计数」，绝不猜命中与否）。
 */
data class PrecheckRules(
    val signals: Map<String, Regex>,
    val rules: List<PrecheckRule>,
    val unusableRuleIds: List<String>,
) {
    /** 有可执行规则才谈得上判定；否则一律降级放行（宁可多发一次请求）。 */
    val available: Boolean get() = rules.isNotEmpty()

    companion object {
        val EMPTY = PrecheckRules(signals = emptyMap(), rules = emptyList(), unusableRuleIds = emptyList())
    }
}

/** 一条已编译规则。`id` 即跨端稳定 `rule_id`（`precheck.<服务端 reason>`，逐字拼接不改名）。 */
data class PrecheckRule(
    val id: String,
    val tier: Int,
    val matcher: PrecheckMatcher,
)

sealed interface PrecheckMatcher {

    /** `type:'regex'` */
    data class Patterns(val regex: Regex) : PrecheckMatcher

    /** `type:'predicate'`：`expr` 已按封闭小语法编译。 */
    data class Expr(val expr: PrecheckExpr) : PrecheckMatcher

    /** `type:'fallback'`：无条件命中（契约保证排最后，是兜底的 `no_signal`）。 */
    data object Always : PrecheckMatcher
}

/**
 * `expr` 的**封闭小语法**（契约 §3，只允许三种形态，不写表达式解析器）：
 * 1. `signal`
 * 2. `signal && signal`（分隔符严格是前后各一个空格的 ` && `）
 * 3. `len<=<int> && signal`
 *
 * 未知形态在编译期被丢弃（见 [PrecheckRules.unusableRuleIds]），不会进到这里。
 */
sealed interface PrecheckExpr {
    data class Signal(val name: String) : PrecheckExpr
    data class And(val left: String, val right: String) : PrecheckExpr
    data class LenLe(val maxLen: Int, val signal: String) : PrecheckExpr
}

/** 判决结果。[NotAvailable] 与「放行」不同：前者表示判据不可用（降级放行）。 */
sealed interface PrecheckVerdict {

    /** 命中 tier ∈ {0,4} 的规则：[ruleId] 直接作为 ⑤ 的 `precheck.*` 计数键。 */
    data class Blocked(val ruleId: String, val tier: Int) : PrecheckVerdict

    /** 第一条命中的规则 tier ∈ {1,2,3}：放行去调模型。 */
    data object Pass : PrecheckVerdict

    /** 无云控 / schema 不认识 / 无可用规则：**降级放行**。 */
    data object NotAvailable : PrecheckVerdict
}

/**
 * precheck 执行器（纯函数，可离线测）。
 *
 * 只做一件事：按 [PrecheckRules.rules] 的顺序取**第一条命中**的 tier。
 * 末尾兜底（fallback）保证有信号/无信号都会被某条命中；万一载荷异常一条都不命中，
 * 返回 [PrecheckVerdict.Pass]（fail-open：宁可多发一次请求，不可静默丢账）。
 */
object PrecheckJudge {

    /** 「应该调模型」的 tier 区间（契约 §3 `should_call_ai = tier in (1,2,3)`）。 */
    private val CALL_AI_TIERS = 1..3

    fun evaluate(input: String, rules: PrecheckRules): PrecheckVerdict {
        if (!rules.available) return PrecheckVerdict.NotAvailable
        // bare_amount 的长度口径与契约逐字一致：input 原串 trim 后的 UTF-16 code unit 数。
        val trimmedLength = input.trim().length
        for (rule in rules.rules) {
            if (rule.matches(input, trimmedLength, rules.signals)) {
                return if (rule.tier in CALL_AI_TIERS) {
                    PrecheckVerdict.Pass
                } else {
                    PrecheckVerdict.Blocked(rule.id, rule.tier)
                }
            }
        }
        return PrecheckVerdict.Pass
    }

    private fun PrecheckRule.matches(input: String, trimmedLength: Int, signals: Map<String, Regex>): Boolean =
        when (val m = matcher) {
            is PrecheckMatcher.Patterns -> m.regex.containsMatchIn(input)
            PrecheckMatcher.Always -> true
            is PrecheckMatcher.Expr -> m.expr.matches(input, trimmedLength, signals)
        }

    private fun PrecheckExpr.matches(input: String, trimmedLength: Int, signals: Map<String, Regex>): Boolean =
        when (this) {
            is PrecheckExpr.Signal -> signals.matches(name, input)
            is PrecheckExpr.And -> signals.matches(left, input) && signals.matches(right, input)
            is PrecheckExpr.LenLe -> trimmedLength <= maxLen && signals.matches(signal, input)
        }

    /** signal 名不存在（编译期已过滤，理论上不会发生）→ 视为不命中，而不是崩溃。 */
    private fun Map<String, Regex>.matches(name: String, input: String): Boolean =
        this[name]?.containsMatchIn(input) == true
}
