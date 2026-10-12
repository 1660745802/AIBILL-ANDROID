package com.aibill.android.data

import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.service.NotificationRulesMapping
import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 内置兜底（`assets/default_rules.json`）的护栏。
 *
 * ## 为什么这份资产需要单独守
 *
 * 规则真源已归属服务端（见 cross-repo 契约 `recognition-flow.md` §9）：
 * 运行时靠云控 + ETag，不再靠客户端发版。客户端这份资产只剩一个职责——
 * **首启尚未拉到云控、或服务端无激活规则时的最小兜底**。
 *
 * 因此它是**冻结**的：不跟随规则演进，只保证「没有任何云控时也能认出最常见的支付通知」。
 * 冻结的东西最容易腐烂，所以这里钉两条：
 *
 * 1. **结构性完整**：生产映射里每个字段都是 `dto.x ?: defaults.x`，资产缺键会把 null
 *    传进非空参数 → 运行时 NPE。所以资产必须自带全部结构键（内容可以很少）。
 * 2. **判据可用**：正则能编译、A11Y 白名单非空、窗口参数合理——否则兜底等于没有兜底。
 *
 * 历史上这份资产是构建期从 `scripts/rules.json` 拷来的（完整规则）；真源归属变了以后
 * 拷贝任务已删除，避免「客户端仓库里还有一份看起来像真源的完整规则」。
 */
class NotificationRulesFallbackTest {

    private val moshi: Moshi = Moshi.Builder().build()

    private fun assetText(): String {
        val candidates = listOf(
            File("src/main/assets/default_rules.json"),
            File("app/src/main/assets/default_rules.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到内置兜底资产（工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    private fun dto() = moshi.adapter(NotificationRulesData::class.java)
        .fromJson(assetText())
        ?: error("内置兜底解析失败")
    private fun parseDto() = dto().rules ?: error("内置兜底缺 rules 节点")

    @Test
    @DisplayName("结构性完整：映射所需键全部存在（缺一个就是运行时 NPE）")
    fun structuralKeysPresent() {
        val r = parseDto()
        // nls
        assertNotNull(r.nls?.paymentSignalRegex, "nls.payment_signal_regex")
        assertNotNull(r.nls?.wechat?.packageName, "nls.wechat.package_name")
        assertNotNull(r.nls?.wechat?.directPassTitles, "nls.wechat.direct_pass_titles")
        assertNotNull(r.nls?.wechat?.messagePrefixes, "nls.wechat.message_prefixes")
        assertNotNull(r.nls?.wechat?.amountSymbols, "nls.wechat.amount_symbols")
        assertNotNull(r.nls?.alipay?.packageName, "nls.alipay.package_name")
        assertNotNull(r.nls?.alipay?.allowedTitleKeywords, "nls.alipay.allowed_title_keywords")
        assertNotNull(r.nls?.bankPackagePatterns, "nls.bank_package_patterns")
        assertNotNull(r.nls?.smsPackages, "nls.sms_packages")
        // a11y
        assertNotNull(r.a11y?.embeddedPaymentApps, "a11y.embedded_payment_apps")
        assertNotNull(r.a11y?.successKeywords, "a11y.success_keywords")
        assertNotNull(r.a11y?.embeddedSuccessKeywords, "a11y.embedded_success_keywords")
        assertNotNull(r.a11y?.commonExcludeKeywords, "a11y.common_exclude_keywords")
        assertNotNull(r.a11y?.wechatAlipayExcludeKeywords, "a11y.wechat_alipay_exclude_keywords")
        assertNotNull(r.a11y?.amountRegex, "a11y.amount_regex")
        assertNotNull(r.a11y?.cooldownMinutes, "a11y.cooldown_minutes")
        // sms / processor
        assertNotNull(r.smsRules?.spamKeywords, "sms.spam_keywords")
        assertNotNull(r.processor?.scoringWindowSeconds, "processor.scoring_window_seconds")
        assertNotNull(r.processor?.dedupWindowSeconds, "processor.dedup_window_seconds")
        assertNotNull(r.processor?.marketingSuffixCutoffs, "processor.marketing_suffix_cutoffs")
        assertNotNull(r.processor?.marketingCommaKeywords, "processor.marketing_comma_keywords")
        assertNotNull(r.processor?.maxAmountCents, "processor.max_amount_cents")
        assertNotNull(r.processor?.minAmountCents, "processor.min_amount_cents")
    }

    @Test
    @DisplayName("兜底自身即可完成映射（不再依赖第二层默认值）")
    fun fallbackIsSelfSufficient() {
        // 第二层兜底故意抛错：能走通就证明资产自己够用
        val rules = NotificationRulesMapping.fromDto(parseDto()) {
            error("内置兜底不完整，退化到了硬编码兜底")
        }
        assertTrue(rules.nls.paymentSignalRegex.isNotBlank())
        assertTrue(rules.a11y.embeddedPaymentApps.isNotEmpty(), "A11Y 白名单为空 → 无障碍渠道全哑")
        assertTrue(rules.a11y.successKeywords.isNotEmpty(), "成功关键词为空 → 无障碍判定全灭")
    }

    @Test
    @DisplayName("判据可用：正则能编译、能认出常见支付文案、窗口参数合理")
    fun criteriaUsable() {
        val rules = NotificationRulesMapping.fromDto(parseDto()) { error("不应回退") }

        // 金额正则必须能编译并且真的能在「¥32.50 / 32.50元」上取出金额
        val amount = Regex(rules.a11y.amountRegex)
        assertNotNull(amount.find("微信支付 ¥32.50"), "金额正则取不到 ¥32.50")
        assertNotNull(amount.find("消费 32.50元"), "金额正则取不到 32.50元")

        // 支付信号正则必须能编译（它驱动「值不值得当账」的第一道判据）
        assertTrue(
            Regex(rules.nls.paymentSignalRegex).containsMatchIn("您尾号1234的储蓄卡消费100.00元"),
            "支付信号正则认不出最常见的银行扣款短信",
        )

        // 窗口参数：评分/去重窗口必须是正数且有界（兜底写错会让全链路时间语义失控）
        assertTrue(rules.processor.scoringWindowSeconds in 1..120)
        assertTrue(rules.processor.dedupWindowSeconds in 1..600)
        assertTrue(rules.processor.maxAmountCents > rules.processor.minAmountCents)
    }
}
