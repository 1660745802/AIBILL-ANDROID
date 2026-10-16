package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 云控 `precheck` 载荷 → 已编译判据（跨端契约 §3）的**编译期**护栏。
 *
 * 这里不测「判据输出对不对」（那在 `PrecheckJudgeTest`），只测 payload 的容错边界：
 * - `flags == ""` 是**合法**的「无 flag」（真载荷里 verb/bare_amount 就是空串）；
 *   只有**键缺失**才算缺失 → 整链不可用（`money.flags='gi'` 丢了会让 `rmb 100` 两端相反）；
 * - **全或无**：任一 signal / 规则编译失败 → 整链不可用（`available == false` → 降级放行）。
 *   部分降级会改变链的结论：正向信号规则被丢而 `no_signal`(tier4) 幸存 → 真交易被判无信号 → P0 误杀；
 * - 未知 `expr` 形态 / 引用缺失 signal → 计入 `unusableRuleIds` 并整链停用；
 * - schema 不认识 → 整体忽略（[PrecheckRules.EMPTY] → 降级放行）。
 */
class PrecheckCompilerTest {

    private val logger = AppLogger(mockk(relaxed = true))

    private fun compile(spec: PrecheckSpec) = PrecheckCompiler.compile(spec, logger)

    @Test
    @DisplayName("flags 成对：缺 flags 键 → 整链不可用；flags == \"\" 是合法的『无 flag』")
    fun missingFlagsMakesSignalUnusable() {
        val rules = compile(PrefilterTestHarness.precheckSpec(moneyFlags = null))

        // 缺 flags 键 → 该 signal 不可用 → **整链停用**（不做部分降级：
        // 正向信号被丢而 no_signal(fallback, tier4) 幸存会把真交易判成无信号 → P0 误杀）
        assertFalse(rules.available)
        assertTrue(rules.rules.isEmpty())
        assertTrue("money" in rules.unusableRuleIds, rules.unusableRuleIds.toString())
    }

    @Test
    @DisplayName("money.flags='gi' 才有 IGNORE_CASE：'RMB 100' 与 'rmb 100' 同判")
    fun moneyFlagsPreserveCaseInsensitivity() {
        // 只留金额/动词判据：避免 bare_amount 把「RMB 100」当光杆数字接走，隔离 flags 的影响
        val rules = listOf(
            PrecheckRuleSpec("precheck.money_verb", tier = 1, type = "predicate", expr = "money && verb"),
            PrecheckRuleSpec("precheck.money_only", tier = 2, type = "predicate", expr = "money"),
            PrecheckRuleSpec("precheck.verb_only", tier = 3, type = "predicate", expr = "verb"),
            PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
        )

        // 'g' 没有 'i'：大写 RMB 不命中 → 一路掉到 no_signal(tier4) → 拦
        val withoutI = compile(PrefilterTestHarness.precheckSpec(moneyFlags = "g", rules = rules))
        assertTrue(withoutI.signals.containsKey("money"))
        assertTrue(
            PrecheckJudge.evaluate("RMB 100", withoutI, SourceChannel.NLS) is PrecheckVerdict.Blocked,
            "丢 'i' 后 'RMB 100' 应被判无信号（正是服务端修过的那次两端相反）",
        )

        val withI = compile(PrefilterTestHarness.precheckSpec(moneyFlags = "gi", rules = rules))
        val verdict = PrecheckJudge.evaluate("RMB 100", withI, SourceChannel.NLS)
        assertTrue(
            verdict is PrecheckVerdict.Pass,
            "money.flags='gi' 时 'RMB 100' 应命中金额 → 放行去调模型，实际=$verdict",
        )
        assertTrue(
            PrecheckJudge.evaluate("rmb 100", withI, SourceChannel.NLS) is PrecheckVerdict.Pass,
            "'rmb 100' 也应命中",
        )
    }

    @Test
    @DisplayName("未知 expr 形态 → 整链不可用（绝不当命中/不命中猜）")
    fun unknownExprIsDroppedAndCounted() {
        val spec = PrefilterTestHarness.precheckSpec(
            rules = listOf(
                // '||' 不在封闭小语法里（契约只允许 signal / a && b / len<=N && signal）
                PrecheckRuleSpec("precheck.money_or_verb", tier = 1, type = "predicate", expr = "money || verb"),
                // 引用了不存在的 signal
                PrecheckRuleSpec("precheck.ghost", tier = 1, type = "predicate", expr = "ghost_signal"),
                // 三个操作数（也非法）
                PrecheckRuleSpec("precheck.three", tier = 1, type = "predicate", expr = "money && verb && money"),
                PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
            )
        )
        val rules = compile(spec)

        // 链上有漂移 → 整体停用（而不是"丢该条、其余照跑"）
        assertEquals(
            listOf("precheck.money_or_verb", "precheck.ghost", "precheck.three"),
            rules.unusableRuleIds,
        )
        assertFalse(rules.available, "链上任何一条不可解析都必须整链停用")
        assertTrue(rules.rules.isEmpty())
    }

    @Test
    @DisplayName("合法 expr 三种形态都能编译")
    fun legalExprFormsCompile() {
        val spec = PrefilterTestHarness.precheckSpec(
            rules = listOf(
                PrecheckRuleSpec("precheck.money_only", tier = 2, type = "predicate", expr = "money"),
                PrecheckRuleSpec("precheck.money_verb", tier = 1, type = "predicate", expr = "money && verb"),
                PrecheckRuleSpec("precheck.bare_amount", tier = 2, type = "predicate", expr = "len<=30 && bare_amount"),
                PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
            )
        )
        val rules = compile(spec)
        assertTrue(rules.unusableRuleIds.isEmpty(), rules.unusableRuleIds.toString())
        assertEquals(4, rules.rules.size)
    }

    @Test
    @DisplayName("schema 不认识 → 整体忽略（空判据 → 降级放行）")
    fun unknownSchemaIsIgnoredEntirely() {
        val rules = compile(PrefilterTestHarness.precheckSpec(schema = 99))
        assertEquals(PrecheckRules.EMPTY, rules)
        assertFalse(rules.available)
    }

    @Test
    @DisplayName("单条正则写坏 → 整链不可用（不做部分降级）")
    fun brokenRegexDropsOnlyThatRule() {
        val spec = PrefilterTestHarness.precheckSpec(
            rules = listOf(
                PrecheckRuleSpec("precheck.bad", tier = 0, type = "regex", pattern = "[未闭合", flags = "i"),
                PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
            )
        )
        val rules = compile(spec)
        assertEquals(listOf("precheck.bad"), rules.unusableRuleIds)
        assertFalse(rules.available, "单条写坏也整链停用（部分降级会改变链的结论）")
        assertTrue(rules.rules.isEmpty())
    }
}
