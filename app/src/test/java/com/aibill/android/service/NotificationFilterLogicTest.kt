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
}
