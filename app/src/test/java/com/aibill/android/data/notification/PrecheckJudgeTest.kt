package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `precheck` 判据语义护栏（跨端契约 §3 / 实施计划 §2.3）。
 *
 * 四条语义必须逐字成立：
 * 1. **顺序即判据顺序**，取第一条命中的 tier；
 * 2. `tier ∈ {1,2,3}` 放行 / `tier ∈ {0,4}` 拦；
 * 3. `type:'fallback'` 无条件命中（兜底排最后）；
 * 4. `bare_amount` 长度 = `input.trim().length`（UTF-16 code unit，与 Kotlin `String.length` 同口径）。
 *
 * 判据不可用（空 / schema 不认识）→ [PrecheckVerdict.NotAvailable]（降级放行）。
 */
class PrecheckJudgeTest {

    private val logger = AppLogger(mockk(relaxed = true))

    private fun compile(spec: PrecheckSpec) = PrecheckCompiler.compile(spec, logger)

    @Test
    @DisplayName("顺序语义：同时命中 tier1 与 tier0 时，第一条命中定 tier（不被后面的 tier0 影响）")
    fun firstHitWins() {
        val rules = compile(
            PrefilterTestHarness.precheckSpec(
                rules = listOf(
                    PrecheckRuleSpec("precheck.money_verb", tier = 1, type = "predicate", expr = "money && verb"),
                    PrecheckRuleSpec("precheck.strong_noise", tier = 0, type = "regex", pattern = "验证码", flags = "i"),
                    PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
                )
            )
        )
        val input = "验证码 微信支付 ¥32.50" // 同时命中 strong_noise 与 money_verb

        assertTrue(
            PrecheckJudge.evaluate(input, rules) is PrecheckVerdict.Pass,
            "第一条命中是 money_verb(tier1) → 必须放行，后面的 tier0 不能反悔",
        )

        // 把 tier0 挪到前面：同一条文本必须变成拦
        val reversed = compile(
            PrefilterTestHarness.precheckSpec(
                rules = listOf(
                    PrecheckRuleSpec("precheck.strong_noise", tier = 0, type = "regex", pattern = "验证码", flags = "i"),
                    PrecheckRuleSpec("precheck.money_verb", tier = 1, type = "predicate", expr = "money && verb"),
                    PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
                )
            )
        )
        assertEquals(PrecheckVerdict.Blocked("precheck.strong_noise", 0), PrecheckJudge.evaluate(input, reversed))
    }

    @Test
    @DisplayName("tier：0/4 拦、1/2/3 放行（不是「有没有 tier」）")
    fun tierMappingIsExhaustive() {
        fun verdictFor(tier: Int, id: String): PrecheckVerdict = PrecheckJudge.evaluate(
            "任意文本",
            compile(PrefilterTestHarness.precheckSpec(rules = listOf(PrecheckRuleSpec(id, tier, "fallback")))),
        )

        // fallback（无条件命中）用每个 tier 各判一次
        assertTrue(verdictFor(0, "precheck.t0") is PrecheckVerdict.Blocked)
        assertTrue(verdictFor(4, "precheck.t4") is PrecheckVerdict.Blocked)
        for (tier in 1..3) {
            assertTrue(
                verdictFor(tier, "precheck.t$tier") is PrecheckVerdict.Pass,
                "tier=$tier 应放行",
            )
        }
    }

    @Test
    @DisplayName("bare_amount 边界：trim 后 30 字命中、31 字不命中；emoji 按 UTF-16 code unit 计")
    fun bareAmountLengthBoundary() {
        val rules = compile(
            PrefilterTestHarness.precheckSpec(
                rules = listOf(
                    PrecheckRuleSpec("precheck.bare_amount", tier = 2, type = "predicate", expr = "len<=30 && bare_amount"),
                    PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
                )
            )
        )

        val len30 = "a".repeat(27) + " 32"
        assertEquals(30, len30.trim().length)
        assertTrue(PrecheckJudge.evaluate(len30, rules) is PrecheckVerdict.Pass, "恰 30 字应命中 bare_amount")

        val len31 = "a".repeat(28) + " 32"
        assertEquals(31, len31.trim().length)
        assertTrue(PrecheckJudge.evaluate(len31, rules) is PrecheckVerdict.Blocked, "31 字不应命中 bare_amount")

        // emoji = 2 个 UTF-16 code unit：13 个 emoji(26) + " 32"(3) = 29 → 命中
        val emoji29 = "😀".repeat(13) + " 32"
        assertEquals(29, emoji29.trim().length)
        assertTrue(PrecheckJudge.evaluate(emoji29, rules) is PrecheckVerdict.Pass, "29 code unit 应命中")

        // 14 个 emoji(28) + " 32"(3) = 31 → 不命中（按 code point 计会误判成 17 ≤ 30）
        val emoji31 = "😀".repeat(14) + " 32"
        assertEquals(31, emoji31.trim().length)
        assertTrue(
            PrecheckJudge.evaluate(emoji31, rules) is PrecheckVerdict.Blocked,
            "emoji 必须按 UTF-16 code unit 计（与 Kotlin String.length / 契约同口径）",
        )

        // 前后空白不参与长度（trim）
        val padded = "  " + "a".repeat(27) + " 32" + "   "
        assertEquals(30, padded.trim().length)
        assertTrue(PrecheckJudge.evaluate(padded, rules) is PrecheckVerdict.Pass, "trim 后 30 字应命中")
    }

    @Test
    @DisplayName("判据不可用：无 precheck / schema 不认识 → NotAvailable（降级放行，不是 Pass）")
    fun unavailableIsNotPass() {
        assertEquals(PrecheckVerdict.NotAvailable, PrecheckJudge.evaluate("任意文本", PrecheckRules.EMPTY))
        val unknownSchema = compile(PrefilterTestHarness.precheckSpec(schema = 42))
        assertEquals(PrecheckVerdict.NotAvailable, PrecheckJudge.evaluate("任意文本", unknownSchema))
    }

    @Test
    @DisplayName("载荷异常一条都不命中 → fail-open 放行（不静默丢账）")
    fun noMatchFailsOpen() {
        val rules = compile(
            PrefilterTestHarness.precheckSpec(
                rules = listOf(
                    PrecheckRuleSpec("precheck.money_only", tier = 2, type = "predicate", expr = "money"),
                )
            )
        )
        // 没有任何信号、也没有 fallback → 不应拦
        assertTrue(PrecheckJudge.evaluate("普通文本", rules) is PrecheckVerdict.Pass)
    }
}
