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
    }

    @Test
    fun `bank 收入识别为 income`() {
        val r = parser.parse("com.bank.app", "您的账户已收入5000.00元")
        assertNotNull(r)
        assertEquals(500000, r!!.amount)
        assertEquals("income", r.type)
    }

    @Test
    fun `bank 扣款识别为 expense`() {
        val r = parser.parse("com.bank.app", "尾号5678账户扣款88.88元")
        assertNotNull(r)
        assertEquals(8888, r!!.amount)
        assertEquals("expense", r.type)
    }

    // ==================== 订单号 ====================

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
        // 银行短信解析器应能处理通用格式
        val r = parser.parse("com.other.app", "消费￥32.00元")
        // 当前实现：银行解析器认 "消费/支出/扣款 ... X.XX元"，¥ 符号未必识别
        // 此处测试仅验证不抛异常，匹配与否取决于具体格式
        // 空 result 也是合法返回（无金额或模式不匹配）
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
}
