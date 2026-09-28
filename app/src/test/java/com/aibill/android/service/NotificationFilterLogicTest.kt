package com.aibill.android.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * NLS 排除层逻辑测试：isLikelyFinancial 判断逻辑
 *
 * 由于 NotificationMonitorService 是 Android Service，这里测试其核心判断逻辑的纯函数版本。
 */
class NotificationFilterLogicTest {

    // 模拟 isLikelyFinancial 的逻辑（与 NotificationMonitorService 保持一致）
    private val wechatDirectPassTitles = listOf("微信支付", "微信支付凭证")
    private val wechatDirectPassTitleContains = listOf("零钱")
    private val wechatMessagePrefixes = listOf("[转账]", "[微信红包]")
    private val wechatAmountSymbols = listOf("¥", "￥")
    private val alipayAllowedTitleKeywords = listOf("交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款")
    private val bankPackagePatterns = listOf("bank", "cmb", "icbc", "ccb", "boc")
    private val paymentSignalRegex = Regex("[¥￥]|支付|付款|到账|转账|消费|扣款|充值|退款")

    private fun isLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
        return when (packageName) {
            "com.tencent.mm" -> {
                if (wechatDirectPassTitles.any { title == it }) return true
                if (wechatDirectPassTitleContains.any { title.contains(it) }) return true
                val textAfterTitle = fullText.substringAfter(title).trim()
                if (wechatMessagePrefixes.any { textAfterTitle.startsWith(it) }) return true
                wechatAmountSymbols.any { textAfterTitle.contains(it) }
            }
            "com.eg.android.AlipayGphone" -> {
                alipayAllowedTitleKeywords.any { title.contains(it) }
            }
            else -> {
                if (bankPackagePatterns.any { packageName.contains(it) || packageName.startsWith(it) }) return true
                paymentSignalRegex.containsMatchIn(fullText)
            }
        }
    }

    // === 微信 ===

    @Test
    fun `微信支付 title 直接放行`() {
        assertTrue(isLikelyFinancial("com.tencent.mm", "微信支付", "微信支付 已支付¥24.00"))
    }

    @Test
    fun `微信 零钱 title contains 放行`() {
        assertTrue(isLikelyFinancial("com.tencent.mm", "零钱通知", "零钱通知 余额变动"))
    }

    @Test
    fun `微信 转账消息前缀放行`() {
        assertTrue(isLikelyFinancial("com.tencent.mm", "张三", "张三 [转账]收到一笔转账"))
    }

    @Test
    fun `微信 含¥符号放行`() {
        assertTrue(isLikelyFinancial("com.tencent.mm", "服务号", "服务号 消费¥32.00"))
    }

    @Test
    fun `微信 普通聊天消息不放行`() {
        assertFalse(isLikelyFinancial("com.tencent.mm", "张三", "张三 今天下班一起吃饭吗"))
    }

    @Test
    fun `微信 表情消息不放行`() {
        assertFalse(isLikelyFinancial("com.tencent.mm", "儒宝", "儒宝 [偷笑][偷笑]"))
    }

    @Test
    fun `微信 群消息不含金额不放行`() {
        assertFalse(isLikelyFinancial("com.tencent.mm", "家人群", "家人群 明天回来吃饭"))
    }

    // === 支付宝 ===

    @Test
    fun `支付宝 交易提醒放行`() {
        assertTrue(isLikelyFinancial("com.eg.android.AlipayGphone", "交易提醒", "交易提醒 你有一笔30元的支出"))
    }

    @Test
    fun `支付宝 花呗放行`() {
        assertTrue(isLikelyFinancial("com.eg.android.AlipayGphone", "花呗还款提醒", "本月花呗待还"))
    }

    @Test
    fun `支付宝 蚂蚁庄园不放行`() {
        assertFalse(isLikelyFinancial("com.eg.android.AlipayGphone", "蚂蚁庄园", "你的小鸡饿了"))
    }

    @Test
    fun `支付宝 积分活动不放行`() {
        assertFalse(isLikelyFinancial("com.eg.android.AlipayGphone", "会员积分", "恭喜获得100积分"))
    }

    // === 银行 ===

    @Test
    fun `招商银行 包名含cmb放行`() {
        assertTrue(isLikelyFinancial("cmb.pb", "招商银行", "信用卡消费30元"))
    }

    @Test
    fun `工商银行 包名含icbc放行`() {
        assertTrue(isLikelyFinancial("com.icbc", "工商银行", "尾号1234消费"))
    }

    // === 其他 App ===

    @Test
    fun `其他App 含支付信号放行`() {
        assertTrue(isLikelyFinancial("com.taobao.taobao", "订单通知", "支付成功 ¥25.00"))
    }

    @Test
    fun `其他App 无支付信号不放行`() {
        assertFalse(isLikelyFinancial("com.taobao.taobao", "物流更新", "您的包裹已发出"))
    }

    @Test
    fun `其他App 促销消息不放行`() {
        assertFalse(isLikelyFinancial("com.taobao.taobao", "618大促", "限时折扣快来抢购"))
    }

    // === default_rule 兜底（v6：白名单外的 App 也走过滤链，2026-09 渠道适配修复） ===
    // 镜像 NotificationMonitorService.handleNotification / SmsReceiverService.handleSms
    // 的真实过滤链（营销词拦截 → default_rule 排除词 + payment_signal）。
    // 注意：与真实代码保持同步，真实逻辑见 service/NotificationMonitorService.kt。

    /** 与 rules.json nls.payment_signal_regex 一致的完整正则 */
    private val paymentSignalRegexFull = Regex(
        "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"
    )

    /** 与 rules.json sms.spam_keywords 一致（子集，覆盖主要营销词） */
    private val smsSpamKeywords = listOf(
        "订购", "退订", "办理", "开通", "激活", "贷款", "借款",
        "提额", "申请", "审批", "邀请", "回复R", "回复TD", "免费领", "中奖", "恭喜",
    )

    /** 与 rules.json nls.default_rule.exclude_content_contains 一致（子集） */
    private val defaultExcludeContent = listOf(
        "秒杀", "礼包", "可领", "待领取", "至高", "首绑", "领取", "抽奖", "优惠券", "满减",
        "红包雨", "限时", "福利", "特惠", "立减", "补贴", "返现", "失效", "卡包",
        "即将过期", "快过期", "再不用", "来不及", "待使用", "中奖", "恭喜", "点击链接",
        "免费领", "贷款", "借款", "提额", "办理", "开通", "邀请",
    )

    /** 强交易特征词（镜像 NotificationRulesManager.STRONG_TXN_MARKERS） */
    private val strongTxnMarkers = listOf(
        "尾号", "卡号", "账户", "储蓄卡", "信用卡", "入账", "支出", "消费",
        "扣款", "转账", "汇款", "还款", "余额",
    )

    /** 营销判定（镜像 NotificationRulesManager.isLikelyMarketing）：命中营销词但不含强交易特征才拦 */
    private fun isLikelyMarketing(text: String): Boolean {
        val hit = smsSpamKeywords.any { text.contains(it) } ||
            defaultExcludeContent.any { text.contains(it) }
        if (!hit) return false
        return strongTxnMarkers.none { text.contains(it) }
    }

    /** SMS 渠道预筛（镜像 SmsReceiverService.handleSms：正则 + 营销词拦截） */
    private fun passesSmsGate(text: String): Boolean {
        if (!paymentSignalRegexFull.containsMatchIn(text)) return false
        return !isLikelyMarketing(text)
    }

    /** NLS 渠道过滤链（镜像 handleNotification：白名单不再一票否决，走 default_rule） */
    private fun passesNlsGate(fullText: String): Boolean {
        if (isLikelyMarketing(fullText)) return false
        return paymentSignalRegexFull.containsMatchIn(fullText)
    }

    @Test
    fun `未配置银行App 动账通知走 default_rule 放行`() {
        // 包名不含 bank/cmb/... 且不在 source_mapping 的农信/城商行/新银行
        assertTrue(passesNlsGate("您尾号3321的储蓄卡账户10月28日14:00支出人民币350.00元"))
    }

    @Test
    fun `未配置银行App 贷款推广被 default_rule 拦截`() {
        assertFalse(passesNlsGate("恭喜您，预审批贷款额度10万元已到账，点击链接领取"))
    }

    @Test
    fun `未配置App 无支付信号不放行`() {
        assertFalse(passesNlsGate("您的包裹已发出"))
    }

    @Test
    fun `银行短信正常放行`() {
        assertTrue(passesSmsGate("您尾号1234储蓄卡账户10月28日支出100.00元，余额3021.55元"))
    }

    @Test
    fun `营销短信被拦截`() {
        assertFalse(passesSmsGate("【XX银行】恭喜您获得贷款额度10万元，回复R办理"))
    }

    @Test
    fun `真实交易含办理字样不被误杀`() {
        // "办理"是营销词，但含尾号/入账等强交易特征 → 交 AI 判定而非过滤层直接拦（宁可多放不漏）
        assertTrue(passesNlsGate("您尾号1234信用卡办理的分期入账3500元"))
        assertTrue(passesSmsGate("您尾号1234信用卡办理的分期入账3500元"))
    }

    @Test
    fun `含强交易特征的弱营销交 AI 判定`() {
        // 营销词 + 尾号并存时不在过滤层拦（防误杀），由 AI 兵底拒识
        assertTrue(passesSmsGate("【工行】您尾号1234的信用卡可办理分期，点击链接"))
    }

    @Test
    fun `短信渠道来源显示为短信`() {
        // 真实函数（非镜像）：NotificationSourceMapping.friendlyName 的 sms: 前缀映射
        assertEquals("短信", com.aibill.android.util.NotificationSourceMapping.friendlyName("sms:10695588"))
    }
}
