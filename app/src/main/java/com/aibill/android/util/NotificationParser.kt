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
                return ParseResult(
                    amount = amountCents,
                    type = "income",
                    description = extractDescription(text),
                    confidence = 75
                )
            }
        }

        // 再尝试支出模式
        for (pattern in WECHAT_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return ParseResult(
                    amount = amountCents,
                    type = "expense",
                    description = extractDescription(text),
                    confidence = 80
                )
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
                return ParseResult(
                    amount = amountCents,
                    type = "income",
                    description = extractDescription(text),
                    confidence = 75
                )
            }
        }

        // 再尝试支出模式
        for (pattern in ALIPAY_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return ParseResult(
                    amount = amountCents,
                    type = "expense",
                    description = extractDescription(text),
                    confidence = 80
                )
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
                return ParseResult(
                    amount = amountCents,
                    type = "income",
                    description = extractDescription(text),
                    confidence = 70
                )
            }
        }

        // 再尝试支出模式
        for (pattern in BANK_EXPENSE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val amountCents = yuanToCents(match.groupValues[1]) ?: continue
                if (amountCents == 0) continue
                return ParseResult(
                    amount = amountCents,
                    type = "expense",
                    description = extractDescription(text),
                    confidence = 70
                )
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
}
