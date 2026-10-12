package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * ④ 接线护栏：云控 precheck 真的在**预筛链末尾**执行，且可降级。
 *
 * - 命中 tier 0/4 → `Verdict.Block(BlockReason.PRECHECK, ruleId)`，计数键 = 服务端 `rule_id`；
 * - 旧服务端无 precheck / schema 不认识 → 行为与今天逐字一致（放行，不拦）；
 * - 未知 expr → 丢该条 + 计入 `precheck_unknown_expr`。
 */
class PrecheckPrefilterWiringTest {

    private fun capture(text: String) = Capture(
        channel = SourceChannel.NLS,
        packageName = "com.tencent.mm",
        title = "微信支付",
        fullText = text,
        receivedAt = 1_000_000L,
    )

    /** 文本先过客户端自有判据（微信 title 直通），再落到 precheck。 */
    private val noiseText = "微信支付 验证码 123456"

    @Test
    @DisplayName("precheck 命中 tier0 → Block(PRECHECK, ruleId)，计数键就是 rule_id")
    fun blockedByPrecheck() = runTest {
        val stats = PrefilterStatsImpl()
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(
            source = InMemoryNotificationRuleSource(
                PrefilterTestHarness.rules(precheck = PrefilterTestHarness.precheckSpec())
            ),
            stats = stats,
        )

        val verdict = prefilter.evaluate(capture(noiseText))
        assertTrue(verdict is Verdict.Block, "云控强噪声判据应拦下：$verdict")
        assertEquals(BlockReason.PRECHECK, (verdict as Verdict.Block).reason)
        assertTrue(verdict.detail.contains("precheck.strong_noise"), verdict.detail)

        val blocked = stats.snapshot().channels.getValue(SourceChannel.NLS).blocked
        assertEquals(1L, blocked["precheck.strong_noise"], "⑤ 的键必须是服务端 rule_id：$blocked")
        assertEquals(1L, stats.snapshot().blockedTotal)
    }

    @Test
    @DisplayName("旧服务端无 precheck → 行为与今天逐字一致（同一条文本放行）")
    fun absentPrecheckDegradesToPass() = runTest {
        val prefilter = PrefilterTestHarness.newPrefilter(
            rules = PrefilterTestHarness.rules(precheck = com.aibill.android.domain.notification.PrecheckSpec.EMPTY),
        )
        assertTrue(
            prefilter.evaluate(capture(noiseText)) is Verdict.Pass,
            "没有云控 precheck 时必须降级放行，不得凭客户端猜测拦下",
        )
    }

    @Test
    @DisplayName("schema 不认识 → 整体降级放行")
    fun unknownSchemaDegradesToPass() = runTest {
        val prefilter = PrefilterTestHarness.newPrefilter(
            rules = PrefilterTestHarness.rules(precheck = PrefilterTestHarness.precheckSpec(schema = 7)),
        )
        assertTrue(prefilter.evaluate(capture(noiseText)) is Verdict.Pass)
    }

    @Test
    @DisplayName("tier1-3 命中 → 照常放行（不误拦真交易）")
    fun tier123PassesToAi() = runTest {
        val prefilter = PrefilterTestHarness.newPrefilter(
            rules = PrefilterTestHarness.rules(precheck = PrefilterTestHarness.precheckSpec()),
        )
        val verdict = prefilter.evaluate(capture("微信支付 已支付¥32.50"))
        assertTrue(verdict is Verdict.Pass, "金额+动词应放行去调模型：$verdict")
    }

    @Test
    @DisplayName("未知 expr：丢该条 + 计入 precheck_unknown_expr（诊断，不算拦截）")
    fun unusableRuleIsCountedOnce() = runTest {
        val stats = PrefilterStatsImpl()
        val spec = PrefilterTestHarness.precheckSpec(
            rules = listOf(
                PrecheckRuleSpec("precheck.money_or_verb", tier = 1, type = "predicate", expr = "money || verb"),
                PrecheckRuleSpec("precheck.money_only", tier = 2, type = "predicate", expr = "money"),
                PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
            )
        )
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(
            source = InMemoryNotificationRuleSource(PrefilterTestHarness.rules(precheck = spec)),
            stats = stats,
        )

        // 两条通知：诊断只按代际记一次，不随通知数膨胀（未知 expr 被丢，剩下的 money_only 放行）
        prefilter.evaluate(capture("微信支付 已支付¥32.50"))
        prefilter.evaluate(capture("微信支付 已支付¥33.50"))

        val counts = stats.snapshot().channels.getValue(SourceChannel.NLS)
        assertEquals(1L, counts.unusableRules[PrefilterStats.PRECHECK_UNKNOWN_EXPR], counts.toString())
        assertEquals(0L, counts.blockedTotal, "诊断不是拦截，不得进 blocked")
    }

    @Test
    @DisplayName("precheck 输入 = 转发给 AI 的 input（清洗营销后缀后）：30 字边界两端同口径")
    fun precheckUsesCleanedInput() = runTest {
        val prefix = "a".repeat(20) + " 32" // 23 字
        val text = prefix + "X".repeat(20)   // 全文 43 字
        val spec = PrefilterTestHarness.precheckSpec(
            rules = listOf(
                PrecheckRuleSpec("precheck.bare_amount", tier = 2, type = "predicate", expr = "len<=30 && bare_amount"),
                PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
            )
        )
        val prefilter = PrefilterTestHarness.newPrefilter(
            rules = PrefilterTestHarness.rules(
                precheck = spec,
                marketingSuffixCutoffs = listOf("XXXX"),
            ),
        )

        // 清洗后 = prefix（23 字）→ bare_amount 命中 tier2 放行；若用未清洗全文（43 字）会落 no_signal 被拦
        assertTrue(
            prefilter.evaluate(capture(text)) is Verdict.Pass,
            "预筛必须用与请求 input 同一个串算 bare_amount 长度",
        )
    }

    @Test
    @DisplayName("客户端自有判据仍排在 precheck 之前（命中即按 client_only.* 计数）")
    fun clientCriteriaStillRunFirst() = runTest {
        val stats = PrefilterStatsImpl()
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(
            source = InMemoryNotificationRuleSource(
                PrefilterTestHarness.rules(
                    spamKeywords = listOf("优惠券"),
                    precheck = PrefilterTestHarness.precheckSpec(),
                )
            ),
            stats = stats,
        )
        val verdict = prefilter.evaluate(capture("微信支付 点击领取优惠券"))
        assertEquals(BlockReason.MARKETING, (verdict as Verdict.Block).reason)
        assertTrue(
            stats.snapshot().channels.getValue(SourceChannel.NLS)
                .blocked.containsKey(PrefilterStats.clientOnlyKey(BlockReason.MARKETING)),
        )
    }
}
