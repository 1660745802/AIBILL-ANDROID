package com.aibill.android.util

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 通知文本解析器
 * 从支付通知中提取金额、类型等信息
 *
 * 支持格式：
 * - 微信："微信支付成功，付款￥32.00" / "向沙县小吃付款￥25.00" / "收款到账￥200.00"
 * - 支付宝："支付宝付款￥15.00" / "收到转账￥200.00"
 * - 银行短信："消费/支出/扣款...100.00元" / "收入/转入...500.00元"
 */
@Singleton
class NotificationParser @Inject constructor() {

    data class ParseResult(
        val amount: Int, // 单位：分
        val type: String, // expense / income
        val description: String? = null,
        /**
         * 抽取出的商家名（如「沙县小吃」「瑞幸咖啡」），用于分类学习的 keyword。
         * 与 description 区别：description 是给 UI 展示的原始文本摘要，
         * 每次金额/时间都不同；merchantName 是稳定的商家标识，可作为学习 key。
         */
        val merchantName: String? = null,
        val confidence: Int // 0-100
    )

    companion object {
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        // 微信支出模式：匹配多种格式
        private val WECHAT_EXPENSE_PATTERNS = listOf(
            Regex("""微信支付.*?[￥¥](\d+\.?\d*)"""),
            Regex("""向.+付款[￥¥](\d+\.?\d*)"""),
            Regex("""付款.*?[￥¥](\d+\.?\d*)"""),
            Regex("""支付成功.*?[￥¥](\d+\.?\d*)"""),
            Regex("""消费[￥¥](\d+\.?\d*)"""),
        )

        // 微信收入模式
        private val WECHAT_INCOME_PATTERNS = listOf(
            Regex("""收款到账[￥¥](\d+\.?\d*)"""),
            Regex("""收到转账[￥¥](\d+\.?\d*)"""),
            Regex("""已到账[￥¥](\d+\.?\d*)"""),
            Regex("""退款.*?[￥¥](\d+\.?\d*)"""),
        )

        // 支付宝支出模式
        private val ALIPAY_EXPENSE_PATTERNS = listOf(
            Regex("""支付宝.*?付款[￥¥](\d+\.?\d*)"""),
            Regex("""付款[￥¥](\d+\.?\d*)"""),
            Regex("""支付成功.*?[￥¥](\d+\.?\d*)"""),
            Regex("""消费[￥¥](\d+\.?\d*)"""),
            Regex("""向.+付款[￥¥](\d+\.?\d*)"""),
        )

        // 支付宝收入模式
        private val ALIPAY_INCOME_PATTERNS = listOf(
            Regex("""收到转账[￥¥](\d+\.?\d*)"""),
            Regex("""收款到账[￥¥](\d+\.?\d*)"""),
            Regex("""已到账[￥¥](\d+\.?\d*)"""),
            Regex("""退款.*?[￥¥](\d+\.?\d*)"""),
        )

        // 银行短信支出模式
        private val BANK_EXPENSE_PATTERNS = listOf(
            Regex("""(?:消费|支出|扣款).*?(\d+\.?\d*)元"""),
        )

        // 银行短信收入模式
        private val BANK_INCOME_PATTERNS = listOf(
            Regex("""(?:收入|转入|到账|入账).*?(\d+\.?\d*)元"""),
        )

        // 商家名抽取（用于分类学习 keyword，要求稳定、与金额/时间无关）
        // 优先级高的优先匹配：「向 X 付款」「X 收款」「X 商家」「尾号 XXXX」等
        private val MERCHANT_PATTERNS = listOf(
            Regex("""向(.+?)付款"""),                // 向沙县小吃付款￥28.50
            Regex("""向(.+?)收款"""),                // 向用户A收款
            Regex("""来自(.+?)(?:的)?(?:付款|转账|红包)"""),
            Regex("""在(.+?)消费"""),                // 在星巴克消费￥50
            Regex("""【(.+?)】"""),                  // 【美团外卖】...
            Regex("""\[(.+?)]"""),                  // [滴滴出行]
        )
    }

    fun parse(packageName: String, text: String): ParseResult? {
        if (text.isBlank()) return null

        return when (packageName) {
            PACKAGE_WECHAT -> parseWechat(text)
            PACKAGE_ALIPAY -> parseAlipay(text)
            else -> parseBankSms(text)
        }
    }

    private fun parseWechat(text: String): ParseResult? {
        // 先尝试收入模式
        for (pattern in WECHAT_INCOME_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "income", 75)
            }
        }

        // 再尝试支出模式
        for (pattern in WECHAT_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "expense", 80)
            }
        }

        return null
    }

    private fun parseAlipay(text: String): ParseResult? {
        // 先尝试收入模式
        for (pattern in ALIPAY_INCOME_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "income", 75)
            }
        }

        // 再尝试支出模式
        for (pattern in ALIPAY_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "expense", 80)
            }
        }

        return null
    }

    private fun parseBankSms(text: String): ParseResult? {
        // 先尝试收入模式
        for (pattern in BANK_INCOME_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "income", 70)
            }
        }

        // 再尝试支出模式
        for (pattern in BANK_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return buildResult(text, amountCents, "expense", 70)
            }
        }

        return null
    }

    private fun yuanToCents(yuanStr: String): Int? {
        val yuan = yuanStr.toDoubleOrNull() ?: return null
        return (yuan * 100).roundToInt()
    }

    private fun extractDescription(text: String): String? {
        return text.take(50).ifBlank { null }
    }

    /**
     * 抽取稳定的商家名（用于分类学习 keyword）。
     * 命中正则取第 1 组；未命中返回 null（让调用方降级用 description）。
     */
    private fun extractMerchant(text: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.getOrNull(1)?.trim()
            if (!name.isNullOrBlank() && name.length in 2..20) {
                return name
            }
        }
        return null
    }

    private fun buildResult(
        text: String,
        amountCents: Int,
        type: String,
        confidence: Int,
    ): ParseResult = ParseResult(
        amount = amountCents,
        type = type,
        description = extractDescription(text),
        merchantName = extractMerchant(text),
        confidence = confidence,
    )
}
