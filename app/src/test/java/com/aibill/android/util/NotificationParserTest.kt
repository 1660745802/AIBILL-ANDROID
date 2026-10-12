package com.aibill.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 覆盖 [NotificationParser]：
 * - 微信 / 支付宝 / 银行短信三类输入的金额/类型识别
 * - 商家名抽取（分类学习 keyword）
 * - 订单号抽取（跨通知去重）
 * - 边界：空白、无效金额、退款、收款到账
 */
class NotificationParserTest {

    private val parser = NotificationParser()

    // ==================== 微信支出 ====================

    @Test
    fun `wechat 微信支付成功`() {
        val r = parser.parse("com.tencent.mm", "微信支付成功，付款￥32.00")
        assertNotNull(r)
        assertEquals(3200, r!!.amount)
        assertEquals("expense", r.type)
    }

    @Test
    fun `wechat 向商家付款`() {
        val r = parser.parse("com.tencent.mm", "向沙县小吃付款￥25.50")
        assertNotNull(r)
        assertEquals(2550, r!!.amount)
        assertEquals("expense", r.type)
        assertEquals("沙县小吃", r.merchantName)
    }

    @Test
    fun `wechat 收款到账识别为 income`() {
        val r = parser.parse("com.tencent.mm", "收款到账￥200.00")
        assertNotNull(r)
        assertEquals(20000, r!!.amount)
        assertEquals("income", r.type)
    }

    @Test
    fun `wechat 退款识别为 income`() {
        val r = parser.parse("com.tencent.mm", "退款￥58.00")
        assertNotNull(r)
        assertEquals(5800, r!!.amount)
        assertEquals("income", r.type)
    }

    // ==================== 支付宝 ====================

    @Test
    fun `alipay 付款识别为 expense`() {
        val r = parser.parse("com.eg.android.AlipayGphone", "支付宝付款￥15.00")
        assertNotNull(r)
        assertEquals(1500, r!!.amount)
        assertEquals("expense", r.type)
    }

    @Test
    fun `alipay 收到转账识别为 income`() {
        val r = parser.parse("com.eg.android.AlipayGphone", "收到转账￥200.00")
        assertNotNull(r)
        assertEquals(20000, r!!.amount)
        assertEquals("income", r.type)
    }

    // ==================== 银行短信 ====================

    @Test
    fun `bank 消费识别为 expense`() {
        val r = parser.parse("com.bank.app", "您尾号1234的信用卡消费100.00元")
        assertNotNull(r)
        assertEquals(10000, r!!.amount)
        assertEquals("expense", r.type)

        // 「扣款」是同一支出表里的另一个词，同一路径不再单开用例
        val deduct = parser.parse("com.bank.app", "尾号5678账户扣款88.88元")
        assertNotNull(deduct)
        assertEquals(8888, deduct!!.amount)
        assertEquals("expense", deduct.type)
    }

    @Test
    fun `bank 收入识别为 income`() {
        val r = parser.parse("com.bank.app", "您的账户已收入5000.00元")
        assertNotNull(r)
        assertEquals(500000, r!!.amount)
        assertEquals("income", r.type)
    }

    // ==================== 判定顺序：收入先于支出 ====================

    @Test
    fun `同一文本同时命中收入与支出句式时先判收入`() {
        // 「消费￥50.00」与「收款到账￥200.00」都能匹配；解析器先跑收入表。
        // 若两条循环顺序被交换，会得到 expense 5000 → 本条变红。
        val r = parser.parse("com.tencent.mm", "消费￥50.00，收款到账￥200.00")
        assertNotNull(r)
        assertEquals("income", r!!.type)
        assertEquals(20000, r.amount)
    }

    // ==================== 订单号 ====================

    @Test
    fun `订单号过短不抽取（关联键最小长度）`() {
        val r = parser.parse("com.bank.app", "消费100.00元，订单号：123")
        assertNotNull(r)
        assertNull(r!!.orderId)
    }

    @Test
    fun `extract order id from 订单号`() {
        val r = parser.parse("com.tencent.mm", "微信支付成功，付款￥32.00，订单号：202507281234567890")
        assertNotNull(r)
        assertEquals("202507281234567890", r!!.orderId)
    }

    @Test
    fun `extract order id from 流水号`() {
        val r = parser.parse("com.bank.app", "消费100.00元，流水号：ABC1234567890")
        assertNotNull(r)
        assertEquals("ABC1234567890", r!!.orderId)
    }

    // ==================== 边界 ====================

    @Test
    fun `blank text returns null`() {
        assertNull(parser.parse("com.tencent.mm", ""))
        assertNull(parser.parse("com.tencent.mm", "   "))
    }

    @Test
    fun `unknown package falls back to bank sms parser`() {
        // 兜底走银行短信解析器：认 "消费/支出/扣款 ... X.XX元"
        val r = parser.parse("com.other.app", "消费￥32.00元")
        assertNotNull(r)
        assertEquals(3200, r!!.amount)
        assertEquals("expense", r.type)
        // 微信句式（¥ 但无「元」单位）银行解析器不认 → 证明走的确实是兜底解析器而非微信解析器
        assertNull(parser.parse("com.other.app", "向沙县小吃付款￥25.00"))
    }

    @Test
    fun `零金额不产生解析结果`() {
        assertNull(parser.parse("com.bank.app", "消费0.00元"))
        assertNull(parser.parse("com.tencent.mm", "收款到账￥0.00"))
    }

    @Test
    fun `extractAmountOnly extracts first amount`() {
        val amt = parser.extractAmountOnly("您的账户收入5000.00元")
        assertEquals(500000, amt)
    }

    @Test
    fun `extractAmountOnly returns null when no amount`() {
        assertNull(parser.extractAmountOnly("通知中心有新消息"))
    }

    @Test
    fun `amount with no decimal point is parsed as integer yuan`() {
        val r = parser.parse("com.tencent.mm", "微信支付成功，付款￥100")
        assertNotNull(r)
        assertEquals(10000, r!!.amount)
    }

    // ==================== 金额边界（Int 溢出回归） ====================

    @Test
    fun `超大金额不被静默 clamp 成 Int 上限，返回 null`() {
        // 旧实现走 Double.roundToInt()，对超范围值静默 clamp 到 2147483647 分，
        // 会得到一个看起来合法的错误金额。
        assertNull(parser.parse("com.bank.app", "消费99999999999999999999元"))
        assertNull(parser.extractAmountOnly("收入999999999999999999.99元"))
    }

    @Test
    fun `恰好 Int 上限内可解析，溢出即 null`() {
        assertEquals(Int.MAX_VALUE, parser.parse("com.bank.app", "消费21474836.47元")!!.amount)
        assertNull(parser.parse("com.bank.app", "消费21474836.48元"))
    }

    @Test
    fun `第三位小数四舍五入到分`() {
        assertEquals(10001, parser.parse("com.bank.app", "消费100.005元")!!.amount)
        assertEquals(10000, parser.parse("com.bank.app", "消费100.004元")!!.amount)
    }
}
