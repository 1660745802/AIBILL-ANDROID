package com.aibill.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PaymentAccessibilityRecognitionTest {

    @Test
    fun `page token is canonical and separates windows`() {
        assertEquals(
            "com.tencent.mm|12|com.tencent.mm.plugin.appbrand.ui.appbrandui",
            PaymentAccessibilityRecognition.pageToken(
                " COM.TENCENT.MM ",
                "Com.Tencent.MM.Plugin.AppBrand.UI.AppBrandUI",
                12,
            ),
        )
        assertNotEquals(
            PaymentAccessibilityRecognition.pageToken("com.tencent.mm", "PayActivity", 12),
            PaymentAccessibilityRecognition.pageToken("com.tencent.mm", "PayActivity", 13),
        )
    }

    @Test
    fun `amount key normalizes equivalent currency forms to cents`() {
        assertEquals("1200", PaymentAccessibilityRecognition.amountKey("¥12"))
        assertEquals("1200", PaymentAccessibilityRecognition.amountKey("￥ 12.00"))
        assertEquals("1200", PaymentAccessibilityRecognition.amountKey("12.0元"))
        assertEquals("123456", PaymentAccessibilityRecognition.amountKey("¥1,234.56"))
        assertNull(PaymentAccessibilityRecognition.amountKey("金额未知"))
    }

    @Test
    fun `success keyword accepts punctuation suffix but rejects semantic suffix`() {
        assertTrue(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功", "支付成功"))
        assertTrue(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功！", "支付成功"))
        assertTrue(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功了", "支付成功"))
        assertTrue(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功了！", "支付成功"))
        assertTrue(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功 ¥12.00", "支付成功"))
        assertFalse(PaymentAccessibilityRecognition.isSuccessKeywordMatch("支付成功率", "支付成功"))
        assertFalse(PaymentAccessibilityRecognition.isSuccessKeywordMatch("查看支付成功的订单", "支付成功"))
    }

    @Test
    fun `exclude keywords use contains semantics`() {
        assertTrue(PaymentAccessibilityRecognition.containsExcludeKeyword(listOf("加入购物车"), listOf("购物车")))
        assertFalse(PaymentAccessibilityRecognition.containsExcludeKeyword(listOf("支付成功"), listOf("购物车", "")))
    }

    @Test
    fun `retry anchor distinguishes content in the same container`() {
        val anchor = PaymentAccessibilityRecognition.retryAnchor(
            listOf("支付成功", "星巴克臻选店", "订单号 12345"),
            "支付成功",
        )
        assertTrue(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("支付成功", "星巴克臻选店", "订单号 12345", "¥12")))
        assertFalse(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("支付成功", "另一商户", "订单号 99999", "¥12")))
    }

    @Test
    fun `state update derives token before root scan`() {
        val state = PaymentAccessibilityRecognition.pageState(" COM.EXAMPLE.APP ", "PayActivity", 42)
        assertEquals("PayActivity", state.first)
        assertEquals("com.example.app|42|payactivity", state.second)
    }

    @Test
    fun `root must match package and valid window`() {
        assertTrue(PaymentAccessibilityRecognition.isRootForEvent("com.example", 4, "com.example", 4))
        assertTrue(PaymentAccessibilityRecognition.isRootForEvent("com.example", -1, "com.example", 4))
        assertTrue(PaymentAccessibilityRecognition.isRootForEvent("com.example", 4, "com.example", -1))
        assertFalse(PaymentAccessibilityRecognition.isRootForEvent("com.other", 4, "com.example", 4))
        assertFalse(PaymentAccessibilityRecognition.isRootForEvent("com.example", 5, "com.example", 4))
    }

    @Test
    fun `payment packages include base apps and normalized embedded apps`() {
        assertEquals(
            setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.example.shop"),
            PaymentAccessibilityRecognition.paymentPackages(listOf(" com.example.shop ", "")),
        )
    }

    @Test
    fun `dedup ttl remains short even when cloud cooldown is long`() {
        assertEquals(30_000L, PaymentAccessibilityRecognition.dedupTtl(5 * 60_000L))
        assertEquals(10_000L, PaymentAccessibilityRecognition.dedupTtl(10_000L))
    }

    @Test
    fun `history range rejects yesterday and yesterday aliases`() {
        val today = LocalDate.of(2026, 8, 10)
        assertFalse(PaymentAccessibilityRecognition.isRecentTextRange(listOf("昨天", "¥12.00"), today))
        assertFalse(PaymentAccessibilityRecognition.isRecentTextRange(listOf("昨日 14:30", "¥12.00"), today))
        assertFalse(PaymentAccessibilityRecognition.isRecentTextRange(listOf("8月9日", "¥12.00"), today))
        assertFalse(PaymentAccessibilityRecognition.isRecentTextRange(listOf("2026-08-09", "¥12.00"), today))
        assertTrue(PaymentAccessibilityRecognition.isRecentTextRange(listOf("订单 8-10", "¥12.00"), today))
        assertTrue(PaymentAccessibilityRecognition.isRecentTextRange(listOf("今天 14:30", "2026-08-10", "¥12.00"), today))
    }
}
