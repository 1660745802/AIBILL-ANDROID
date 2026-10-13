package com.aibill.android.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A11Y profile 逐键回退矩阵（服务端 `docs/API.md` 附录 C §10）。
 *
 * 三件事必须钉死：profile 未写的键**继续用全局**（不是整块替换）；`exclude_keywords`
 * **出现即完全替换**；单键非法回退全局、schema 不认识忽略整块，且**都告警**。
 */
class A11yProfileResolverTest {

    private val global = A11yGlobalProfile(
        successKeywords = listOf("支付成功"),
        embeddedSuccessKeywords = listOf("订单支付成功"),
        commonExcludeKeywords = listOf("购物车"),
        wechatAlipayExcludeKeywords = listOf("朋友圈"),
        amountRegex = Regex("""[¥￥](\d+\.?\d{0,2})"""),
        merchantLabels = listOf("收款方"),
    )

    private class Warns {
        val entries = mutableListOf<String>()
        val warn = A11yProfileResolver.Warn { pkg, key, reason -> entries += "$pkg|$key|$reason" }
    }

    private fun resolve(embedded: Boolean = false, override: A11yProfileOverride? = null): Pair<A11yPackageProfile, Warns> {
        val w = Warns()
        return A11yProfileResolver.resolve("com.x", embedded, override, global, w.warn) to w
    }

    // ── 逐键回退 ──

    @Test
    @DisplayName("无覆盖 → 全局值；非 embedded 排除词 = wechat_alipay ⊕ common")
    fun defaultsFromGlobal() {
        val (p, w) = resolve()
        assertEquals(listOf("支付成功"), p.successKeywords)
        assertEquals(listOf("朋友圈", "购物车"), p.excludeKeywords)
        assertEquals(global.amountRegex.pattern, p.amountRegex.pattern)
        assertEquals(listOf("收款方"), p.merchantLabels)
        assertEquals(A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS, p.listPageHeadChars)
        assertTrue(w.entries.isEmpty(), "无覆盖不该有任何回退告警：${w.entries}")
    }

    @Test
    @DisplayName("无覆盖 + embedded → 成功词并入 embedded；排除词只用 common（与改造前一致）")
    fun defaultsForEmbedded() {
        val (p, _) = resolve(embedded = true)
        assertEquals(listOf("支付成功", "订单支付成功"), p.successKeywords)
        assertEquals(listOf("购物车"), p.excludeKeywords)
    }

    @Test
    @DisplayName("只覆盖 success_keywords → 其余键必须继续从全局取（逐键，不是整块替换）")
    fun singleKeyOverrideKeepsGlobalForOtherKeys() {
        val (p, w) = resolve(
            override = A11yProfileOverride(successKeywords = listOf("已付款")),
        )
        assertEquals(listOf("已付款"), p.successKeywords)
        // 其余全部来自全局
        assertEquals(listOf("朋友圈", "购物车"), p.excludeKeywords)
        assertEquals(listOf("收款方"), p.merchantLabels)
        assertEquals(A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS, p.listPageHeadChars)
        assertSame(global.amountRegex, p.amountRegex)
        assertTrue(w.entries.isEmpty(), w.entries.toString())
    }

    @Test
    @DisplayName("覆盖 exclude_keywords → 完全替换该包排除词（embedded / 非 embedded 都用它）")
    fun excludeOverrideFullyReplaces() {
        val override = A11yProfileOverride(excludeKeywords = listOf("充值页"))
        assertEquals(listOf("充值页"), resolve(embedded = false, override = override).first.excludeKeywords)
        assertEquals(listOf("充值页"), resolve(embedded = true, override = override).first.excludeKeywords)
    }

    @Test
    @DisplayName("覆盖 merchant_labels → 生效（不影响其它键）")
    fun merchantLabelsOverride() {
        val (p, _) = resolve(override = A11yProfileOverride(merchantLabels = listOf("店铺名")))
        assertEquals(listOf("店铺名"), p.merchantLabels)
        assertEquals(listOf("支付成功"), p.successKeywords)
    }

    // ── 单键非法回退 + 告警 ──

    @Test
    @DisplayName("amount_regex 编译失败 → 回退全局 + 告警（带 package + key）")
    fun invalidAmountRegexFallsBackToGlobal() {
        val (p, w) = resolve(override = A11yProfileOverride(amountRegex = "[("))
        assertSame(global.amountRegex, p.amountRegex)
        assertEquals(1, w.entries.size)
        assertTrue(w.entries.single().startsWith("com.x|amount_regex|"), w.entries.single())
    }

    @Test
    @DisplayName("amount_regex 合法 → 覆盖生效；空串按非法回退全局 + 告警")
    fun validAmountRegexOverrides() {
        val (p, _) = resolve(override = A11yProfileOverride(amountRegex = """[¥￥](\d+)"""))
        assertEquals("""[¥￥](\d+)""", p.amountRegex.pattern)

        val (blank, w) = resolve(override = A11yProfileOverride(amountRegex = "   "))
        assertSame(global.amountRegex, blank.amountRegex)
        assertEquals(1, w.entries.size, "空串不可编译 → 按非法回退并告警")
    }

    @Test
    @DisplayName("list_page_head_chars 覆盖生效；非正 → 回退内置 24 + 告警")
    fun headCharsOverrideAndFallback() {
        assertEquals(40, resolve(override = A11yProfileOverride(listPageHeadChars = 40)).first.listPageHeadChars)

        val (p, w) = resolve(override = A11yProfileOverride(listPageHeadChars = 0))
        assertEquals(A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS, p.listPageHeadChars)
        assertEquals(1, w.entries.size)
        assertTrue(w.entries.single().startsWith("com.x|list_page_head_chars|"), w.entries.single())
    }

    // ── schema 守卫 ──

    @Test
    @DisplayName("profiles_schema 缺失/≠1 → 忽略整个 app_profiles + 告警")
    fun unknownSchemaIgnoresAllProfiles() {
        val overrides = mapOf("com.x" to A11yProfileOverride(successKeywords = listOf("已付款")))

        for (schema in listOf(null, 0, 2)) {
            val w = Warns()
            val result = A11yProfileResolver.resolveAll(schema, overrides, emptySet(), global, w.warn)
            assertTrue(result.isEmpty(), "schema=$schema 必须忽略全部 profile")
            assertEquals(1, w.entries.size, "schema=$schema 必须告警")
            assertTrue(w.entries.single().contains("profiles_schema"), w.entries.single())
        }
    }

    @Test
    @DisplayName("profiles_schema=1 → 逐包解析（embedded 由包名集合决定）")
    fun knownSchemaResolvesEachPackage() {
        val overrides = mapOf(
            "com.embed" to A11yProfileOverride(successKeywords = listOf("已付款")),
            "com.other" to A11yProfileOverride(excludeKeywords = listOf("充值页")),
        )
        val w = Warns()
        val result = A11yProfileResolver.resolveAll(
            profilesSchema = A11yProfileResolver.PROFILES_SCHEMA,
            overrides = overrides,
            embeddedPaymentApps = setOf("com.embed"),
            global = global,
            warn = w.warn,
        )
        assertEquals(setOf("com.embed", "com.other"), result.keys)
        assertEquals(listOf("已付款", "订单支付成功"), result.getValue("com.embed").successKeywords)
        assertEquals(listOf("充值页"), result.getValue("com.other").excludeKeywords)
        assertTrue(w.entries.isEmpty(), w.entries.toString())
    }

    @Test
    @DisplayName("app_profiles 为空 → 空结果且不告警（旧服务端/无 profile 时零副作用）")
    fun emptyOverridesProduceNothing() {
        val w = Warns()
        val result = A11yProfileResolver.resolveAll(null, emptyMap(), emptySet(), global, w.warn)
        assertTrue(result.isEmpty())
        assertTrue(w.entries.isEmpty())
        assertFalse(result.containsKey("com.x"))
    }
}
