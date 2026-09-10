package com.aibill.android.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * per_package 规则驱动的通知识别测试。
 *
 * 复现 evaluateByConfig 的逻辑（与 NotificationMonitorService 保持一致），
 * 验证新规则驱动判断 + 向后兼容 fallback。
 */
class PerPackageRuleTest {

    // === matches 匹配 ===

    @Test
    fun `精确包名匹配`() {
        val rule = PerPackageRule(packageName = "com.tencent.mm")
        assertTrue(rule.matches("com.tencent.mm"))
        assertFalse(rule.matches("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `包名模式匹配（contains）`() {
        val rule = PerPackageRule(packagePattern = "bank")
        assertTrue(rule.matches("com.icbc.bank"))
        assertTrue(rule.matches("bankofchina.app"))
        assertFalse(rule.matches("com.tencent.mm"))
    }

    @Test
    fun `无匹配条件返回false`() {
        val rule = PerPackageRule()
        assertFalse(rule.matches("any.package"))
    }

    // === evaluateByConfig 逻辑（复现） ===

    private fun evaluateByConfig(cfg: PerPackageRule, title: String, fullText: String): Boolean {
        if (cfg.excludeTitleContains.any { title.contains(it) }) return false
        if (cfg.excludeContentContains.any { fullText.contains(it) }) return false
        if (cfg.passAll) return true
        if (cfg.passTitleExact.any { title == it }) return true
        if (cfg.passTitleContains.any { title.contains(it) }) return true
        val textAfterTitle = fullText.substringAfter(title).trim()
        if (cfg.passMsgPrefix.any { textAfterTitle.startsWith(it) }) return true
        if (cfg.requireAmountSymbol) {
            return fullText.contains("¥") || fullText.contains("￥")
        }
        return false
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
    fun `微信支付放行`() {
        assertTrue(evaluateByConfig(wechatRule, "微信支付", "微信支付 已支付¥24.00"))
    }

    @Test
    fun `微信转账前缀放行`() {
        assertTrue(evaluateByConfig(wechatRule, "张三", "张三 [转账]收到转账"))
    }

    @Test
    fun `微信含金额符号放行`() {
        assertTrue(evaluateByConfig(wechatRule, "服务号", "服务号 ¥32.00"))
    }

    @Test
    fun `微信营销红包被排除`() {
        // 即使含金额符号，因命中排除词"礼包"直接拒绝
        assertFalse(evaluateByConfig(wechatRule, "活动", "活动 领取¥5礼包"))
    }

    @Test
    fun `微信普通聊天不放行`() {
        assertFalse(evaluateByConfig(wechatRule, "张三", "张三 明天吃饭"))
    }

    // === 支付宝配置 ===

    private val alipayRule = PerPackageRule(
        packageName = "com.eg.android.AlipayGphone",
        passTitleContains = listOf("交易提醒", "支付", "账单"),
        excludeTitleContains = listOf("卡包", "会员", "蚂蚁"),
        excludeContentContains = listOf("秒杀", "礼包", "可领", "失效", "即将过期"),
    )

    @Test
    fun `支付宝交易提醒放行`() {
        assertTrue(evaluateByConfig(alipayRule, "交易提醒", "交易提醒 你有一笔30元支出"))
    }

    @Test
    fun `支付宝卡包营销被title排除`() {
        // 案例：支付宝卡包 你有天天秒杀13.50元红包今晚失效
        assertFalse(evaluateByConfig(alipayRule, "支付宝卡包", "支付宝卡包 你有天天秒杀13.50元红包今晚失效"))
    }

    @Test
    fun `支付宝失效红包被content排除`() {
        assertFalse(evaluateByConfig(alipayRule, "交易提醒", "交易提醒 红包即将失效"))
    }

    // === 银行配置 ===

    private val bankRule = PerPackageRule(
        packagePattern = "bank",
        passAll = true,
        excludeContentContains = listOf("贷款", "借款", "提额", "办理"),
    )

    @Test
    fun `银行交易全放行`() {
        assertTrue(evaluateByConfig(bankRule, "招商银行", "尾号1234消费30元"))
    }

    @Test
    fun `银行贷款营销被排除`() {
        assertFalse(evaluateByConfig(bankRule, "招商银行", "您可申请30万贷款额度"))
    }

    // === 排除优先级 ===

    @Test
    fun `排除词优先于放行`() {
        val rule = PerPackageRule(
            packageName = "test",
            passAll = true,
            excludeContentContains = listOf("广告"),
        )
        // passAll=true 但命中排除词 → 拒绝
        assertFalse(evaluateByConfig(rule, "标题", "这是广告内容"))
    }
}
