package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **真实载荷回归护栏（P0）**：服务端真实路由导出的 `precheck` 必须能经生产路径
 * 编译出一条可用的判据链，且 `flags == ""`（无 flag）不再被误判为「缺失」。
 *
 * 背景：commit 8694c09 修了两个用近似夹具掩盖掉的缺陷——
 * 1. `verb` / `bare_amount` 的真实 `flags` 是**空串**（合法 = 无 flag），旧实现把
 *    `flags.isBlank()` 也当缺失 → 两个 signal 双双失效、引用它们的规则连带被丢；
 * 2. 部分降级会改变链的结论 → 真交易被判「无信号」→ 拦 → P0 误杀；现为**全或无**。
 *
 * 本用例直接用 [PrecheckRealPayload]（真载荷 + 生产编译路径），不再造近似 spec。
 */
class PrecheckRealPayloadTest {

    private val logger = AppLogger(mockk(relaxed = true))

    @Test
    @DisplayName("真载荷：整链可用（14 条规则、0 条不可用），且顺序语义来自服务端原序")
    fun realPayloadCompilesIntoUsableChain() {
        val rules = PrecheckRealPayload.compileRules(logger)

        assertTrue(rules.available, "真载荷必须编译出一条可用的判据链（否则降级放行=降噪全丢）")
        assertTrue(rules.unusableRuleIds.isEmpty(), "真载荷不应有不可用规则：${rules.unusableRuleIds}")
        assertEquals(14, rules.rules.size, "真载荷规则条数变了（服务端改判据需同步播报 content_hash）")

        // 顺序即判据顺序（契约 §3）：只钉首尾，不全文硬编码 14 条 id。
        // ⚠️ 真实载荷的首条是 `precheck.social_redpacket`（tier0 强噪声链首），
        //    不是手写近似夹具 `PrefilterTestHarness` 里的 `precheck.strong_noise`。
        assertEquals("precheck.social_redpacket", rules.rules.first().id)
        assertEquals("precheck.no_signal", rules.rules.last().id)
    }

    @Test
    @DisplayName("夹具身份：content_hash / corpus_hash 钉死服务端重导版本（换载荷必须被看见）")
    fun fixtureIdentityIsPinnedToServerExport() {
        // ⚠️ 服务端重导载荷会换 content_hash——本断言必须同步更新；
        //    否则「测试跑的仍是旧夹具」会静默通过（本次就是千分位自足那次重导）。
        assertEquals(
            "4a74ba0a13aef8dc22a37bc01ad99ebaeb1c665054366f31bc95d6f0f8c1f391",
            PrecheckRealPayload.contentHash(),
            "content_hash 变了：服务端重导了 precheck 载荷，请核对差异后更新此断言",
        )
        assertEquals(
            "025e7de0a94002cead2472031b250a570865231bc18bcf8a25a11cd1e152afe6",
            PrecheckRealPayload.corpusHash(),
            "corpus_hash 变了：对账语料版本已变，需重新对账",
        )
    }

    @Test
    @DisplayName("money pattern 自足千分位：1,000元 / 24,210.99元 / ¥1,000.50 命中；1,23元 / 2.555元 不命中")
    fun moneyPatternHandlesThousandsSeparators() {
        val money = PrecheckRealPayload.compileRules(logger).signals.getValue("money")

        // 服务端改为「pattern 自足」：不再依赖分类前的隐式 stripThousands()
        listOf("1,000元", "24,210.99元", "¥1,000.50").forEach {
            assertTrue(money.containsMatchIn(it), "千分位/货币金额必须命中：$it")
        }
        // 契约 §3 的两个畸形反例：两端必须一致地「不命中」
        listOf("1,23元", "2.555元").forEach {
            assertFalse(money.containsMatchIn(it), "畸形金额不得命中（契约 §3 反例）：$it")
        }
    }

    @Test
    @DisplayName("flags 空串回归：verb / bare_amount 的 signals 必须编译成功（真载荷的 flags 就是 \"\"）")
    fun emptyFlagsSignalsCompile() {
        val rules = PrecheckRealPayload.compileRules(logger)

        // ⚠️ 这一条就是防 `flags:""` 回归的断言：旧实现把空串当「缺 flags」→
        //    这两个 signal 会被丢掉 → 引用它们的 8 条规则连带不可用 → 整链原本会降级/误判。
        assertTrue("verb" in rules.signals.keys, "verb 的 flags 是空串（合法=无 flag），必须编译成功")
        assertTrue("bare_amount" in rules.signals.keys, "bare_amount 的 flags 是空串，必须编译成功")

        // 不只是「键在」——还要「真的能用」：verb 认交易动词、bare_amount 认光杆数字。
        assertTrue(rules.signals.getValue("verb").containsMatchIn("微信支付 已支付¥32.50"))
        assertTrue(rules.signals.getValue("bare_amount").containsMatchIn("肯德基45"))

        // money 的 flags 是 "gi"：大小写不敏感必须保住（服务端曾因丢 'i' 出现两端相反）
        assertTrue(rules.signals.getValue("money").containsMatchIn("rmb 100"))

        // 正向信号链能真的走到「放行」：真交易不得被兜底 no_signal 接走。
        assertTrue(
            PrecheckJudge.evaluate("微信支付 已支付¥32.50", rules) is PrecheckVerdict.Pass,
            "真载荷下真交易必须命中正向规则放行（这正是 flags 空串缺陷的表现面）",
        )
    }
}
