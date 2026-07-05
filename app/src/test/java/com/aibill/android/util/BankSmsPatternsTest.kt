package com.aibill.android.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * BankSmsPatterns 单元测试
 *
 * 覆盖关键不变量：
 * 1. 非银行号码发送 → null
 * 2. 银行号码 + 消费格式 → 正确提取金额/类型/银行名
 * 3. 银行号码 + 收入格式 → 正确识别为 income
 * 4. 尾号提取
 * 5. 流水号提取
 * 6. 各银行格式覆盖
 */
class BankSmsPatternsTest {

    private val parser = BankSmsPatterns()

    // === 非银行短信预筛 ===

    @Test
    fun `non-bank sender returns null`() {
        assertNull(parser.parse("10086", "您的本月话费为50元"))
        assertNull(parser.parse("12345", "验证码123456"))
        assertNull(parser.parse("朋友", "明天一起吃饭"))
    }

    // === 工商银行 ===

    @Test
    fun `ICBC expense - standard format`() {
        val result = parser.parse("95588", "【工商银行】您尾号1234的储蓄卡消费人民币32.50，余额1000.00元")
        assertNotNull(result)
        assertEquals(3250, result!!.amount)
        assertEquals("expense", result.type)
        assertEquals("工商银行", result.bankName)
        assertEquals("1234", result.cardLast4)
    }

    @Test
    fun `ICBC income - salary`() {
        val result = parser.parse("95588", "【工商银行】您尾号5678的储蓄卡工资到账8500.00元，余额15000.00元")
        assertNotNull(result)
        assertEquals(850000, result!!.amount)
        assertEquals("income", result.type)
        assertEquals("工商银行", result.bankName)
    }

    // === 建设银行 ===

    @Test
    fun `CCB expense`() {
        val result = parser.parse("95533", "您尾号8899的信用卡消费120.00元，商户：星巴克")
        assertNotNull(result)
        assertEquals(12000, result!!.amount)
        assertEquals("expense", result.type)
        assertEquals("建设银行", result.bankName)
        assertEquals("8899", result.cardLast4)
    }

    // === 招商银行 ===

    @Test
    fun `CMB expense with RMB format`() {
        val result = parser.parse("95555", "您尾号3456的卡消费人民币 68.80，余额2000元")
        assertNotNull(result)
        assertEquals(6880, result!!.amount)
        assertEquals("expense", result.type)
        assertEquals("招商银行", result.bankName)
    }

    @Test
    fun `CMB income transfer`() {
        val result = parser.parse("95555", "您尾号3456的卡转入200.00元，余额5000.00元")
        assertNotNull(result)
        assertEquals(20000, result!!.amount)
        assertEquals("income", result.type)
    }

    // === 中国银行 ===

    @Test
    fun `BOC expense`() {
        val result = parser.parse("95566", "中国银行：您尾号7890的信用卡扣款45.90元")
        assertNotNull(result)
        assertEquals(4590, result!!.amount)
        assertEquals("expense", result.type)
        assertEquals("中国银行", result.bankName)
    }

    // === 农业银行 ===

    @Test
    fun `ABC income`() {
        val result = parser.parse("95599", "您尾号2345的储蓄卡收入3000.00元，摘要工资")
        assertNotNull(result)
        assertEquals(300000, result!!.amount)
        assertEquals("income", result.type)
        assertEquals("农业银行", result.bankName)
    }

    // === 1069 通道号银行短信 ===

    @Test
    fun `1069 channel - bank sms with content keyword`() {
        val result = parser.parse("1069012345", "【平安银行】您尾号6789的信用卡消费50.00元，商户美团外卖")
        assertNotNull(result)
        assertEquals(5000, result!!.amount)
        assertEquals("expense", result.type)
        assertEquals("平安银行", result.bankName)
    }

    // === 流水号提取 ===

    @Test
    fun `order id extraction`() {
        val result = parser.parse("95533", "您尾号1111的卡消费100.00元，流水号：ABC123456")
        assertNotNull(result)
        assertEquals("ABC123456", result!!.orderId)
    }

    // === 代扣格式 ===

    @Test
    fun `auto debit format`() {
        val result = parser.parse("95588", "快捷支付扣款15.00元，商户：爱奇艺会员")
        assertNotNull(result)
        assertEquals(1500, result!!.amount)
        assertEquals("expense", result.type)
    }

    // === 退款 ===

    @Test
    fun `refund as income`() {
        val result = parser.parse("95555", "您尾号4567的卡退款到账88.00元")
        assertNotNull(result)
        assertEquals(8800, result!!.amount)
        assertEquals("income", result.type)
    }

    // === 无法识别的银行短信 ===

    @Test
    fun `bank sender but non-transaction content returns null`() {
        assertNull(parser.parse("95588", "您的信用卡账单已出，最低还款额500元"))
        // 这里没有"消费/扣款/到账"等关键词，不应匹配
    }

    // === 金额边界 ===

    @Test
    fun `zero amount returns null`() {
        assertNull(parser.parse("95533", "您尾号1234的卡消费0.00元"))
    }

    @Test
    fun `large amount`() {
        val result = parser.parse("95588", "您尾号9999的储蓄卡转入50000.00元")
        assertNotNull(result)
        assertEquals(5000000, result!!.amount)
        assertEquals("income", result.type)
    }
}
