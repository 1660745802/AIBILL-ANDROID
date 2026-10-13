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
    fun `retry anchor excludes keyword and mutable amounts`() {
        val anchor = PaymentAccessibilityRecognition.retryAnchor(
            listOf("支付成功", "¥12.00", "星巴克臻选店", "订单号 12345"),
            "支付成功",
        )
        // 锚点 = 同一容器里稳定的文本：成功词与金额（不同笔不同）不得入锚；
        // 否则重试判重被金额变化干扰。
        assertEquals(setOf("星巴克臻选店", "订单号 12345"), anchor)
        // 命中阈值 = minOf(2, anchor.size)：≥2 个仍相同才算同一次重试
        assertTrue(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("支付成功", "¥12.00", "星巴克臻选店", "订单号 12345")))
        assertFalse(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("支付成功", "¥12.00", "另一商户", "订单号 99999")))
    }

    @Test
    fun `single-element retry anchor degrades threshold to one`() {
        val anchor = PaymentAccessibilityRecognition.retryAnchor(
            listOf("支付成功", "星巴克臻选店"),
            "支付成功",
        )
        assertEquals(setOf("星巴克臻选店"), anchor)
        assertTrue(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("星巴克臻选店")))
        assertFalse(PaymentAccessibilityRecognition.matchesRetryAnchor(anchor, listOf("别的店")))
    }

    @Test
    fun `state update derives token before root scan`() {
        val state = PaymentAccessibilityRecognition.pageState(" COM.EXAMPLE.APP ", "PayActivity", 42)
        assertEquals("PayActivity", state.first)
        assertEquals("com.example.app|42|payactivity", state.second)
    }

    @Test
    fun `root 必须同包（跨包才拒绝，不比较 windowId）`() {
        assertTrue(PaymentAccessibilityRecognition.isRootForEvent("com.example", "com.example"))
        // 同包不同 windowId（微信通话 / 支付宝指纹层）都是独立 window，严格相等会把真交易整批丢掉，
        // 只有跨包（或 root 为 null）才是真错位
        assertFalse(PaymentAccessibilityRecognition.isRootForEvent("com.other", "com.example"))
        assertFalse(PaymentAccessibilityRecognition.isRootForEvent(null, "com.example"))
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
    fun `list page heuristic requires both amounts and list keyword`() {
        val keywords = PaymentAccessibilityRecognition.DEFAULT_LIST_KEYWORDS
        val regex = PaymentAccessibilityRecognition.LIST_PAGE_AMOUNT_REGEX
        // 正例：≥3 个金额 + 列表词
        assertTrue(
            PaymentAccessibilityRecognition.looksLikeListPage(
                "账单 ¥12.00 ¥32.50 32.50元 1,000元",
                regex,
                keywords,
            ),
        )
        // 反例：结果页（¥ 金额 + 商户，无列表词）
        assertFalse(
            PaymentAccessibilityRecognition.looksLikeListPage("支付成功 ¥32.50 星巴克", regex, keywords),
        )
        // 边界：实付+原价+运费 = 3 个金额但无列表词 → 不得判为列表页
        assertFalse(
            PaymentAccessibilityRecognition.looksLikeListPage(
                "实付 ¥32.50 原价 ¥45.00 运费 ¥10.00",
                regex,
                keywords,
            ),
        )
        // 有列表词但只有 1 个金额 → 不得判为列表页
        assertFalse(
            PaymentAccessibilityRecognition.looksLikeListPage("账单 ¥32.50", regex, keywords),
        )
        // 反例护栏：真结果页 3 个金额 + 底部「查看账单」入口（列表词在标题区之外）→ 放行。
        // 若只看「金额≥3 且命中列表词」，这页会被当历史账单拦掉 = P0 误杀漏账。
        assertFalse(
            PaymentAccessibilityRecognition.looksLikeListPage(
                "支付成功 实付 ¥32.50 原价 ¥45.00 优惠 ¥12.50 查看账单",
                regex,
                keywords,
            ),
        )
        // 标题区的列表词仍必须命中：把「账单」放在页首，同文本就应判为列表页
        assertTrue(
            PaymentAccessibilityRecognition.looksLikeListPage(
                "账单 实付 ¥32.50 原价 ¥45.00 优惠 ¥12.50 查看明细",
                regex,
                keywords,
            ),
        )
    }

    @Test
    fun `list page head window is configurable per profile`() {
        val keywords = PaymentAccessibilityRecognition.DEFAULT_LIST_KEYWORDS
        val regex = PaymentAccessibilityRecognition.LIST_PAGE_AMOUNT_REGEX
        // 「账单」在第 11 个字符处；内置窗口 24 → 命中；profile 收窄到 4 → 窗口外 → 放行。
        val page = "0123456789账单 ¥1.00 ¥2.00 ¥3.00"
        assertTrue(PaymentAccessibilityRecognition.looksLikeListPage(page, regex, keywords))
        assertFalse(
            PaymentAccessibilityRecognition.looksLikeListPage(page, regex, keywords, headChars = 4),
        )
        assertTrue(
            PaymentAccessibilityRecognition.looksLikeListPage(page, regex, keywords, headChars = 40),
        )
    }

    @Test
    fun `count amount like texts covers currency and thousand separator forms`() {
        assertEquals(
            3,
            PaymentAccessibilityRecognition.countAmountLikeTexts(
                listOf("¥32.50", "32.50元", "1,000元", "星巴克", "¥", "32.50"),
                PaymentAccessibilityRecognition.LIST_PAGE_AMOUNT_REGEX,
            ),
        )
    }

    @Test
    fun `amount regex handles full-width space separators`() {
        val regex = PaymentAccessibilityRecognition.LIST_PAGE_AMOUNT_REGEX
        // 服务端实测样例：¥ + U+3000 + 数字 / 数字 + U+3000 + 元。
        // JVM 默认 \s 不含 U+3000；改用显式空白类后才命中（否则真金额被漏计）。
        assertTrue(regex.containsMatchIn("¥\u300032.50"), "¥ + 全角空格 + 金额必须命中")
        assertTrue(regex.containsMatchIn("100\u3000元"), "金额 + 全角空格 + 元必须命中")
        assertEquals(
            2,
            PaymentAccessibilityRecognition.countAmountLikeTexts(
                listOf("¥\u300032.50", "100\u3000元"),
                regex,
            ),
        )
        // 端到端：命中后的金额去重键也必须认得全角空格（`trim()` 去掉首尾 U+3000）。
        assertEquals("3250", PaymentAccessibilityRecognition.amountKey("¥\u300032.50"))
        assertEquals("10000", PaymentAccessibilityRecognition.amountKey("100\u3000元"))
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
