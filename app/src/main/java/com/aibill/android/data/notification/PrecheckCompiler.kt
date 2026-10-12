package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrecheckExpr
import com.aibill.android.domain.notification.PrecheckMatcher
import com.aibill.android.domain.notification.PrecheckRule
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.domain.notification.PrecheckSignalSpec
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.util.AppLogger

/**
 * 云控 `precheck` 原始 spec → 已编译 [PrecheckRules]（`deriveSnapshot` 里每代际跑一次）。
 *
 * ## 全或无（any-failure 即整链不可用）
 *
 * ⚠️ 这里**不是**逐条容错：任一 signal / 规则编译失败就返回不可用（`available == false`）→
 * 调用方降级放行。原因见 `compile()` 里的注释：部分降级会改变链的**结论**
 * （正向信号规则被丢、而 `no_signal`(fallback, tier 4) 幸存 → 真交易被判「无信号」→ 拦 → P0 误杀）。
 * 宁可整链不可用（多发一次请求），也不用残缺的链去拦人。
 *
 * 一条规则坏掉只丢**那一条**，其余照常执行；被判坏的规则 id 记进
 * [PrecheckRules.unusableRuleIds]，由预筛计入诊断计数 `precheck_unknown_expr`。
 * 为什么不做成整体失败：规则是服务端下发的**运行期数据**，客户端没有「拒绝启动」的选择；
 * 而整体丢弃会让降噪能力凭空消失。单条丢弃最多是多发一次请求，符合契约取舍。
 *
 * ## 什么算「坏」
 *
 * - `schema != 1`（不认识）→ 整体忽略（契约 §3，降级）；
 * - signal 缺 `flags` / flags 含 `gim` 以外的字符 / 正则编译失败 → 该 signal 不可用；
 * - 规则引用了不可用的 signal / `expr` 不是封闭小语法 / `tier` 越界 / `type` 未知 →
 *   **整链不可用**（该条 id 计入 `unusableRuleIds` 供诊断，但 `rules` 清空）。
 */
internal object PrecheckCompiler {

    /** 契约只允许 `g` / `i` / `m`（`g` 是 JS 概念，Java 正则天然全局，忽略即可）。 */
    private val ALLOWED_FLAGS = setOf('g', 'i', 'm')

    /** signal 名 / expr 里的标识符：`signals` 的键（契约只产出 money/verb/bare_amount）。 */
    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** `len<=<int>` 形态。 */
    private val LEN_LE = Regex("^len<=(\\d+)$")

    private const val AND_SEPARATOR = " && "
    private const val TYPE_REGEX = "regex"
    private const val TYPE_PREDICATE = "predicate"
    private const val TYPE_FALLBACK = "fallback"

    fun compile(spec: PrecheckSpec?, logger: AppLogger?): PrecheckRules {
        if (spec == null || spec.schema != PrecheckSpec.SUPPORTED_SCHEMA) return PrecheckRules.EMPTY

        val signals = compileSignals(spec.signals, logger)

        // ⚠️ **全或无**：任一 signal 编译失败（或数量对不上）即整链不可用。
        // 理由：部分降级会改变链的**结论**——正向信号规则被丢、而 `no_signal`(fallback, tier 4)
        // 幸存时，真交易会被判成「无信号」→ 拦 → **P0 误杀**。宁可整链不可用（降级放行、
        // 只是多发一次请求），也不让残缺的链去拦人。
        if (signals.size != spec.signals.size) {
            return PrecheckRules(
                signals = emptyMap(),
                rules = emptyList(),
                unusableRuleIds = spec.signals.keys.filterNot { it in signals },
            )
        }

        val rules = ArrayList<PrecheckRule>(spec.rules.size)
        val unusable = ArrayList<String>()

        for (rule in spec.rules) {
            val compiled = compileRule(rule, signals, logger)
            if (compiled == null) {
                unusable += rule.id.ifBlank { UNKNOWN_RULE_ID }
            } else {
                rules += compiled
            }
        }
        // 同理：任何一条规则编译失败（载荷漂移）→ 整链不可用，不做部分降级
        if (unusable.isNotEmpty()) {
            return PrecheckRules(signals = emptyMap(), rules = emptyList(), unusableRuleIds = unusable)
        }
        return PrecheckRules(signals = signals, rules = rules, unusableRuleIds = emptyList())
    }

    private fun compileSignals(specs: Map<String, PrecheckSignalSpec>, logger: AppLogger?): Map<String, Regex> {
        val compiled = LinkedHashMap<String, Regex>(specs.size)
        for ((name, signal) in specs) {
            val regex = compilePattern(signal.pattern, signal.flags, "signal=$name", logger) ?: continue
            compiled[name] = regex
        }
        return compiled
    }

    private fun compileRule(
        rule: PrecheckRuleSpec,
        signals: Map<String, Regex>,
        logger: AppLogger?,
    ): PrecheckRule? {
        // tier 恒为 0..4 的整数（契约 §3：没有 tier:null）；越界说明载荷已漂移 →
        // 该条不可用（调用方据此把**整链**停用，而不是只丢这一条）。
        if (rule.tier !in 0..4) {
            warn(logger, rule.id, "tier 越界(${rule.tier})")
            return null
        }
        val matcher = when (rule.type) {
            TYPE_FALLBACK -> PrecheckMatcher.Always
            TYPE_REGEX -> {
                val regex = compilePattern(rule.pattern, rule.flags, rule.id, logger) ?: return null
                PrecheckMatcher.Patterns(regex)
            }
            TYPE_PREDICATE -> {
                val expr = parseExpr(rule.expr, signals) ?: run {
                    warn(logger, rule.id, "expr 未知形态或引用缺失 signal: ${rule.expr}")
                    return null
                }
                PrecheckMatcher.Expr(expr)
            }
            else -> {
                warn(logger, rule.id, "未知 type=${rule.type}")
                return null
            }
        }
        return PrecheckRule(id = rule.id.ifBlank { UNKNOWN_RULE_ID }, tier = rule.tier, matcher = matcher)
    }

    /**
     * `pattern` + `flags` **成对**编译：任缺其一即不可用（不得把缺 flags 静默当 `''`——
     * `money.flags='gi'` 丢了会让 `rmb 100` 两端判定相反，服务端已因此修过一次 bug）。
     */
    private fun compilePattern(pattern: String?, flags: String?, what: String, logger: AppLogger?): Regex? {
        if (pattern.isNullOrBlank()) {
            warn(logger, what, "缺 pattern")
            return null
        }
        // ⚠️ `flags == ""` 是**合法**的「无 flag」（服务端 verb / bare_amount 就是空串）；
        // 只有**键缺失**（null）才算缺失。把空串也当缺失会一次性丢掉半个判据链
        // （真载荷验证发现：verb/bare_amount 全是空串 → 链上大半规则连带失效）。
        if (flags == null) {
            warn(logger, what, "缺 flags 键（不得当空 flag 用）")
            return null
        }
        val illegal = flags.filterNot { it in ALLOWED_FLAGS }
        if (illegal.isNotEmpty()) {
            warn(logger, what, "flags 含非法字符 '$illegal': $flags")
            return null
        }
        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options += RegexOption.IGNORE_CASE
        if ('m' in flags) options += RegexOption.MULTILINE
        return try {
            Regex(pattern, options)
        } catch (e: Exception) {
            warn(logger, what, "正则编译失败: ${e.message}")
            null
        }
    }

    /**
     * `expr` 封闭小语法（契约 §3）：`signal` / `signal && signal` / `len<=<int> && signal`。
     * 其余形态一律返回 null（调用方据此把整链停用 + 计数），**不猜命中与否**。
     */
    private fun parseExpr(expr: String?, signals: Map<String, Regex>): PrecheckExpr? {
        if (expr.isNullOrBlank()) return null
        if (!expr.contains(AND_SEPARATOR)) {
            return signalRef(expr, signals)?.let { PrecheckExpr.Signal(it) }
        }
        val parts = expr.split(AND_SEPARATOR)
        if (parts.size != 2) return null
        val (left, right) = parts
        LEN_LE.matchEntire(left)?.let { m ->
            val signal = signalRef(right, signals) ?: return null
            return PrecheckExpr.LenLe(maxLen = m.groupValues[1].toIntOrNull() ?: return null, signal = signal)
        }
        val l = signalRef(left, signals) ?: return null
        val r = signalRef(right, signals) ?: return null
        return PrecheckExpr.And(l, r)
    }

    /** 合法标识符**且**是 `signals` 的键才可用（契约 §3「signal 必须是 signals 的键」）。 */
    private fun signalRef(name: String, signals: Map<String, Regex>): String? =
        name.takeIf { IDENTIFIER.matches(it) && it in signals }

    private fun warn(logger: AppLogger?, ruleId: String, reason: String) {
        logger?.warn("PRECHECK", "规则不可用，已忽略: $ruleId ($reason)")
    }

    private const val UNKNOWN_RULE_ID = "precheck.unknown"
}
