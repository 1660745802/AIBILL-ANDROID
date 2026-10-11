package com.aibill.android.service

import com.aibill.android.data.notification.PrefilterTestHarness
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.MarketingCriteria
import com.aibill.android.domain.notification.PerPackageCriteria
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * per_package 规则驱动的通知识别测试。
 *
 * ## 本文件不再有任何判据拷贝
 *
 * 收口前这里有一个私有的 `evaluateByConfig(cfg, title, fullText)`，它是生产判据的一份
 * 手抄（注释里明写着与生产判据保持同步）。
 * 现在它只提供**规则配置**（云控 `nls.per_package` 的一行），判定统一调生产预筛链。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerPackageRuleTest {

    // === matches 匹配 ===

    @Test
    fun `精确包名匹配`() {
        val rule = PerPackageCriteria(packageName = "com.tencent.mm")
        assertTrue(rule.matches("com.tencent.mm"))
        assertFalse(rule.matches("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `包名模式匹配（contains）`() {
        val rule = PerPackageCriteria(packagePattern = "bank")
        assertTrue(rule.matches("com.icbc.bank"))
        assertTrue(rule.matches("bankofchina.app"))
        assertFalse(rule.matches("com.tencent.mm"))
    }

    @Test
    fun `无匹配条件返回false`() {
        val rule = PerPackageCriteria()
        assertFalse(rule.matches("any.package"))
    }

    /** 云控 DTO 映射过来的行与领域判据是同一个形状——映射本身零逻辑。 */
    @Test
    fun `云控 PerPackageRule 映射后匹配行为不变`() {
        val dtoRule = PerPackageRule(
            packageName = "com.tencent.mm",
            passTitleExact = listOf("微信支付"),
            excludeContentContains = listOf("秒杀"),
        )
        val source = com.aibill.android.data.notification.InMemoryNotificationRuleSource(
            PrefilterTestHarness.rules(perPackage = listOf(dtoRule))
        )
        val criteria = source.snapshot().perPackage.single()
        assertTrue(criteria.matches("com.tencent.mm"))
        assertTrue(criteria.passTitleExact.contains("微信支付"))
        assertTrue(criteria.excludeContentContains.contains("秒杀"))
    }

    // === evaluateByConfig 判定（直接打生产预筛） ===

    /** 把一条 per_package 规则装进生产判据链，返回是否放行。 */
    private suspend fun passes(rule: PerPackageRule, title: String, fullText: String): Boolean {
        val prefilter = PrefilterTestHarness.newPrefilter(
            PrefilterTestHarness.rules(perPackage = listOf(rule))
        )
        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.NLS,
                packageName = rule.packageName ?: "com.example.bank.app",
                title = title,
                fullText = fullText,
                receivedAt = 1_000_000L,
            )
        )
        return verdict is Verdict.Pass
    }

    // === 微信配置 ===

    private val wechatRule = PerPackageRule(
        packageName = "com.tencent.mm",
        passTitleExact = listOf("微信支付", "微信支付凭证"),
        passTitleContains = listOf("零钱"),
        passMsgPrefix = listOf("[转账]", "[微信红包]"),
        requireAmountSymbol = true,
        excludeContentContains = listOf("秒杀", "礼包", "可领", "失效", "即将过期"),
    )

    @Test
    fun `微信支付放行`() = runTest {
        assertTrue(passes(wechatRule, "微信支付", "微信支付 已支付¥24.00"))
    }

    @Test
    fun `微信转账前缀放行`() = runTest {
        assertTrue(passes(wechatRule, "张三", "张三 [转账]收到转账"))
    }

    @Test
    fun `微信含金额符号放行`() = runTest {
        assertTrue(passes(wechatRule, "服务号", "服务号 ¥32.00"))
    }

    @Test
    fun `微信营销红包被排除`() = runTest {
        // 即使含金额符号，因命中排除词"礼包"直接拒绝
        assertFalse(passes(wechatRule, "活动", "活动 领取¥5礼包"))
    }

    @Test
    fun `微信普通聊天不放行`() = runTest {
        assertFalse(passes(wechatRule, "张三", "张三 明天吃饭"))
    }

    // === 支付宝配置 ===

    // 支付宝线上真实配置（与 scripts/rules.json nls.per_package 保持一致）。
    // 之前这里是精简版，缺了「领取」等词，导致 B1 回归用例无法复现线上行为。
    private val alipayRule = PerPackageRule(
        packageName = "com.eg.android.AlipayGphone",
        passTitleContains = listOf("交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款"),
        excludeTitleContains = listOf("卡包", "会员", "蚂蚁", "积分", "领奖", "活动"),
        excludeContentContains = listOf(
            "秒杀", "礼包", "可领", "待领取", "至高", "首绑", "领取", "抽奖", "优惠券",
            "满减", "限时", "福利", "特惠", "立减", "补贴", "返现", "失效",
            "即将过期", "快过期", "再不用", "来不及",
        ),
    )

    @Test
    fun `支付宝交易提醒放行`() = runTest {
        assertTrue(passes(alipayRule, "交易提醒", "交易提醒 你有一笔30元支出"))
    }

    @Test
    fun `支付宝卡包营销被title排除`() = runTest {
        // 案例：支付宝卡包 你有天天秒杀13.50元红包今晚失效
        assertFalse(passes(alipayRule, "支付宝卡包", "支付宝卡包 你有天天秒杀13.50元红包今晚失效"))
    }

    @Test
    fun `支付宝失效红包被content排除`() = runTest {
        assertFalse(passes(alipayRule, "交易提醒", "交易提醒 红包即将失效"))
    }

    // === 银行配置 ===

    private val bankRule = PerPackageRule(
        packagePattern = "bank",
        passAll = true,
        excludeContentContains = listOf("贷款", "借款", "提额", "办理"),
    )

    @Test
    fun `银行交易全放行`() = runTest {
        assertTrue(passes(bankRule, "招商银行", "尾号1234消费30元"))
    }

    @Test
    fun `银行贷款营销被排除`() = runTest {
        assertFalse(passes(bankRule, "招商银行", "您可申请30万贷款额度"))
    }

    // === 排除优先级 ===

    @Test
    fun `排除词优先于放行`() = runTest {
        val rule = PerPackageRule(
            packageName = "com.example.test",
            passAll = true,
            excludeContentContains = listOf("广告"),
        )
        // passAll=true 但命中排除词 → 拒绝
        assertFalse(passes(rule, "标题", "这是广告内容"))
    }

    // ===== B1：排除词 + 强交易特征不应误杀（2026-10-01 真实漏记）=====

    @Test
    fun `B1 回归：交易提醒含支出+领取 → 不应被排除词误杀`() = runTest {
        // 真实事故原文：因命中「领取」被直接拒收，导致 ¥20.00 真实交易漏记
        val real = "交易提醒 你有一笔20.00元的支出，点击领取9个支付宝积分。"
        assertTrue(
            passes(alipayRule, "交易提醒", real),
            "含强交易特征「支出」时不应被排除词一票否决",
        )
    }

    @Test
    fun `B1 回归：纯营销仍被拦截（豁免不放松整体口径）`() = runTest {
        // 无强交易特征的营销文案仍应被拒
        val ad = "领券中心 立即领取50元满减券，下单立减10元"
        assertFalse(passes(alipayRule, "领券中心", ad))
    }

    @Test
    fun `B1 回归：卡包类仍被 title 排除词拦截`() = runTest {
        assertFalse(passes(alipayRule, "卡包", "你有 3 张优惠券待使用"))
    }

    @Test
    fun `强交易特征豁免语义：命中词表且有特征 → 不判营销`() {
        val words = listOf("领取", "优惠")
        assertFalse(MarketingCriteria.isMarketingByWords("你有一笔20.00元的支出，点击领取积分", words))
        assertTrue(MarketingCriteria.isMarketingByWords("点击领取优惠券", words))
        assertFalse(MarketingCriteria.isMarketingByWords("不含任何词", words))
        assertFalse(MarketingCriteria.isMarketingByWords("含词", emptyList()))
    }
}
