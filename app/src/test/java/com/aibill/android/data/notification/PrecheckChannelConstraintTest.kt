package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckSignalSpec
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **信号级入口约束**（`channel_agnostic` + `signals.*.channels`）。
 *
 * ## 背景
 *
 * `bare_amount`（短句里的光杆数字）是为**手打短句**设计的信号，但通知侧 100% 只产噪声：
 * 线上 104 条靠它进来的记录里，15 条真账全是手打短句（`工资50` / `吃饭花了456` / `电费99.5`），
 * 89 条通知侧全是空结果、**零真账**——形态是群编号 `209[嗨]`、手机号 `18504204877`、
 * `预计12:33送达` 的 `12`、`90%的用户已更新` 的 `90`。服务端因此把这条信号收紧到
 * `channels: ['manual']` + `channel_agnostic: false`，客户端只有 nls/sms/a11y 三个入口
 * → 永远不吃这条信号。
 *
 * ## 四条必须钉住
 *
 * 1. **约束生效**：`channel_agnostic:false` + `channels` → 该入口上信号按 false 求值；
 * 2. **继续往下匹配**（最容易写错的一处）：信号不成立**不等于**直接 tier 0，
 *    判据链必须按 `rules[]` 顺序继续匹配——否则「只有交易动词」的真账会被整条杀成噪声；
 * 3. **老载荷行为逐字不变**：`channel_agnostic:true` / 字段缺省 / `channels` 缺省 → 忽略约束；
 * 4. **fail-open 方向**：`channels: []` 视为「不约束」而不是「哪都不成立」——
 *    后者会让信号静默失效、正向信号没了而 `no_signal`(tier 4) 还在 → P0 误杀。
 */
class PrecheckChannelConstraintTest {

    private val logger = AppLogger(mockk(relaxed = true))

    private fun compile(spec: PrecheckSpec) = PrecheckCompiler.compile(spec, logger)

    /** 服务端真载荷形状：`bare_amount` 带 `channels=['manual']`。 */
    private fun specWithBareAmountChannels(
        channelAgnostic: Boolean,
        channels: List<String>?,
        rules: List<PrecheckRuleSpec> = PrefilterTestHarness.defaultPrecheckRules(),
    ) = PrefilterTestHarness.precheckSpec(
        rules = rules,
        channelAgnostic = channelAgnostic,
        signalOverrides = mapOf(
            "bare_amount" to PrecheckSignalSpec(
                pattern = PrefilterTestHarness.BARE_AMOUNT_PATTERN,
                flags = "i",
                channels = channels,
            ),
        ),
    )

    /** 判据链上的首个命中规则 id（把 tier 全改 0，用生产 judge 探一次）。 */
    private fun matchedRuleId(input: String, rules: PrecheckRules, ch: SourceChannel): String? {
        val probe = rules.copy(rules = rules.rules.map { it.copy(tier = 0) })
        return (PrecheckJudge.evaluate(input, probe, ch) as? PrecheckVerdict.Blocked)?.ruleId
    }

    // ───────────────── 1. 约束生效 ─────────────────

    @Test
    @DisplayName("channels=['manual'] + channel_agnostic:false → nls 上 bare_amount 不成立，落 no_signal 拦")
    fun `约束在通知入口生效`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("manual")))
        assertTrue(rules.available, "链必须可用")

        // 「肯德基45」这种手打短句形态：正则能匹配，但 nls 入口不吃这条信号
        assertEquals(
            "precheck.no_signal",
            matchedRuleId("午饭 22", rules, SourceChannel.NLS),
            "nls 上 bare_amount 不成立后应继续往下、落到兜底 no_signal",
        )
    }

    @Test
    @DisplayName("客户端三个入口（nls/sms/a11y）全部不吃 manual 约束")
    fun `三个入口都不吃`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("manual")))
        for (ch in listOf(SourceChannel.NLS, SourceChannel.SMS, SourceChannel.A11Y)) {
            assertEquals(
                "precheck.no_signal",
                matchedRuleId("午饭 22", rules, ch),
                "$ch 上不该再由 bare_amount 放行",
            )
        }
    }

    @Test
    @DisplayName("入口名口径与 ⑤ 上报/服务端一致（小写 logTag）")
    fun `入口名小写`() {
        assertEquals("nls", PrefilterReport.channelName(SourceChannel.NLS))
        assertEquals("sms", PrefilterReport.channelName(SourceChannel.SMS))
        assertEquals("a11y", PrefilterReport.channelName(SourceChannel.A11Y))
    }

    // ───────────────── 2. 继续往下匹配（最容易写错） ─────────────────

    @Test
    @DisplayName("信号不成立≠直接 tier 0：只有交易动词的真账必须继续匹配到 verb_only 放行")
    fun `不成立时继续往下匹配而不是直接拦`() {
        // 这条最关键：若实现写成「信号不成立 → tier 0」，
        // 「到账了」这类**只有动词、没金额**的真账会被直接杀成噪声（= 丢账）。
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("manual")))

        val verdict = PrecheckJudge.evaluate("到账了", rules, SourceChannel.NLS)
        assertTrue(
            verdict is PrecheckVerdict.Pass,
            "只有交易动词的真账必须继续往下匹配到 verb_only(tier 3) 放行，实际=$verdict",
        )
        assertEquals("precheck.verb_only", matchedRuleId("到账了", rules, SourceChannel.NLS))
    }

    @Test
    @DisplayName("未被约束的信号不受影响：money+verb 仍放行")
    fun `未约束信号不受影响`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("manual")))
        assertTrue(
            PrecheckJudge.evaluate("微信支付 已支付¥32.50", rules, SourceChannel.NLS) is PrecheckVerdict.Pass,
            "money/verb 没加约束，行为必须逐字不变",
        )
    }

    // ───────────────── 3. 老载荷行为不变 ─────────────────

    @Test
    @DisplayName("channel_agnostic=true（老载荷）→ 忽略 channels，bare_amount 照常放行")
    fun `老载荷忽略约束`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = true, channels = listOf("manual")))
        assertTrue(
            PrecheckJudge.evaluate("午饭 22", rules, SourceChannel.NLS) is PrecheckVerdict.Pass,
            "老服务端不下发 channel_agnostic（或为 true）→ 必须与今天行为逐字一致",
        )
    }

    @Test
    @DisplayName("fields 全缺（老服务端载荷）→ 行为与今天一致")
    fun `字段缺省行为不变`() {
        // channelAgnostic 缺省 true + channels 缺省 null：这是「还没跟服务端」时的状态
        val rules = compile(PrefilterTestHarness.precheckSpec())
        assertTrue(rules.available)
        assertNull(rules.signals.getValue("bare_amount").channels, "缺省即无约束")
        assertTrue(
            PrecheckJudge.evaluate("午饭 22", rules, SourceChannel.NLS) is PrecheckVerdict.Pass,
            "老载荷下裸数字短句仍应放行（多调一次模型，安全方向）",
        )
    }

    @Test
    @DisplayName("channel_agnostic:false 但某条信号没有 channels → 只有那一条被约束")
    fun `逐条生效`() {
        val spec = PrefilterTestHarness.precheckSpec(
            channelAgnostic = false,
            signalOverrides = mapOf(
                "verb" to PrecheckSignalSpec(PrefilterTestHarness.VERB_PATTERN, "i", channels = listOf("manual")),
            ),
        )
        val rules = compile(spec)

        assertNull(rules.signals.getValue("money").channels, "money 没声明 channels → 不约束")
        assertEquals(setOf("manual"), rules.signals.getValue("verb").channels)
        assertNull(rules.signals.getValue("bare_amount").channels, "bare_amount 没声明 channels → 不约束")
    }

    // ───────────────── 4. fail-open 方向 ─────────────────

    @Test
    @DisplayName("channels=[] 视为「不约束」而不是「哪都不成立」（避免信号静默失效 → P0 误杀）")
    fun `空 channels 往放行方向退`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = emptyList()))
        assertNull(
            rules.signals.getValue("bare_amount").channels,
            "空数组按不约束处理：宁可多调一次模型，也不能让正向信号静默失效",
        )
        assertTrue(
            PrecheckJudge.evaluate("午饭 22", rules, SourceChannel.NLS) is PrecheckVerdict.Pass,
        )
    }

    @Test
    @DisplayName("channels 含空白项 → 去空白后仍不约束")
    fun `空白项不构成约束`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("", "  ")))
        assertNull(rules.signals.getValue("bare_amount").channels)
    }

    @Test
    @DisplayName("整链仍可用（约束不参与「全或无」判定，它不是编译失败）")
    fun `约束不影响链可用性`() {
        val rules = compile(specWithBareAmountChannels(channelAgnostic = false, channels = listOf("manual")))
        assertTrue(rules.available)
        assertFalse(rules.unusableRuleIds.isNotEmpty(), "约束生效不该把规则标成不可用")
    }
}
