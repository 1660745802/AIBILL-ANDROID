package com.aibill.android.util

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 银行短信格式解析库
 *
 * 覆盖 60+ 家中国银行的短信格式，从短信中提取金额、类型、银行名等信息。
 * 阶段 3.2 会补充完整的银行格式正则。
 */
@Singleton
class BankSmsPatterns @Inject constructor() {

    data class SmsParseResult(
        val amount: Int, // 单位：分
        val type: String, // expense / income
        val bankName: String?, // 银行名称
        val description: String?, // 交易描述
        val orderId: String? = null, // 流水号
        val cardLast4: String? = null, // 尾号
    )

    companion object {
        // 银行短信发送号码前缀（用于预筛）
        private val BANK_SENDER_PATTERNS = listOf(
            Regex("""^95\d{3,4}$"""),  // 95588(工行) 95533(建行) 等
            Regex("""^1069\d+"""),     // 1069 开头的银行短信通道
            Regex("""^106\d+"""),      // 106 开头的通道号
        )

        // === 支出模式 ===
        private val EXPENSE_PATTERNS = listOf(
            // 通用格式：尾号XXXX...消费/支出/扣款...N元/N.NN元
            Regex("""尾号(\d{4}).*?(?:消费|支出|扣款|付款|交易).*?(\d+\.?\d*)元"""),
            // 格式变体：人民币/RMB
            Regex("""尾号(\d{4}).*?(?:消费|支出|扣款|付款).*?(?:人民币|RMB)\s*(\d+\.?\d*)"""),
            // 格式：您...卡...消费...元
            Regex("""(?:储蓄卡|信用卡|借记卡).*?(?:消费|支出|扣款|付款|交易).*?(\d+\.?\d*)元"""),
            // 微信/支付宝代扣
            Regex("""(?:快捷支付|代扣|自动扣款).*?(\d+\.?\d*)元"""),
        )

        // === 收入模式 ===
        private val INCOME_PATTERNS = listOf(
            Regex("""尾号(\d{4}).*?(?:收入|转入|到账|入账|存入|汇入).*?(\d+\.?\d*)元"""),
            Regex("""尾号(\d{4}).*?(?:收入|转入|到账|入账).*?(?:人民币|RMB)\s*(\d+\.?\d*)"""),
            Regex("""(?:储蓄卡|借记卡).*?(?:收入|转入|到账|入账|存入).*?(\d+\.?\d*)元"""),
            Regex("""(?:工资|薪资|报销|退款|红包).*?(\d+\.?\d*)元"""),
        )

        // === 流水号提取 ===
        private val ORDER_PATTERNS = listOf(
            Regex("""流水号[：:\s]*(\w+)"""),
            Regex("""交易号[：:\s]*(\w+)"""),
            Regex("""凭证号[：:\s]*(\w+)"""),
        )

        // === 银行名识别（根据 95xxx 号码） ===
        private val BANK_BY_SENDER = mapOf(
            "95588" to "工商银行",
            "95533" to "建设银行",
            "95566" to "中国银行",
            "95599" to "农业银行",
            "95568" to "民生银行",
            "95555" to "招商银行",
            "95559" to "交通银行",
            "95580" to "邮储银行",
            "95511" to "平安银行",
            "95561" to "兴业银行",
            "95558" to "中信银行",
            "95508" to "广发银行",
            "95528" to "浦发银行",
            "95526" to "华夏银行",
            "95595" to "光大银行",
            "95527" to "江苏银行",
            "95568" to "民生银行",
            "95371" to "宁波银行",
        )
    }

    /**
     * 解析银行短信
     * @param sender 短信发送号码
     * @param text 短信全文
     * @return 解析结果，非银行短信或无法识别返回 null
     */
    fun parse(sender: String, text: String): SmsParseResult? {
        // 预筛：发送号码必须匹配银行通道模式
        val isBankSender = BANK_SENDER_PATTERNS.any { it.matches(sender) }
        if (!isBankSender) return null

        // 识别银行名
        val bankName = identifyBank(sender, text)

        // 尝试收入模式
        for (pattern in INCOME_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val amount = extractAmount(match) ?: continue
            if (amount == 0) continue
            val cardLast4 = extractCardLast4(match)
            return SmsParseResult(
                amount = amount,
                type = "income",
                bankName = bankName,
                description = text.take(50),
                orderId = extractOrderId(text),
                cardLast4 = cardLast4,
            )
        }

        // 尝试支出模式
        for (pattern in EXPENSE_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val amount = extractAmount(match) ?: continue
            if (amount == 0) continue
            val cardLast4 = extractCardLast4(match)
            return SmsParseResult(
                amount = amount,
                type = "expense",
                bankName = bankName,
                description = text.take(50),
                orderId = extractOrderId(text),
                cardLast4 = cardLast4,
            )
        }

        return null
    }

    private fun identifyBank(sender: String, text: String): String? {
        // 优先用发送号码识别
        for ((prefix, name) in BANK_BY_SENDER) {
            if (sender.startsWith(prefix)) return name
        }
        // 兜底：从短信内容中识别
        val bankKeywords = listOf(
            "工商银行" to "工商银行", "建设银行" to "建设银行", "中国银行" to "中国银行",
            "农业银行" to "农业银行", "招商银行" to "招商银行", "交通银行" to "交通银行",
            "邮储银行" to "邮储银行", "民生银行" to "民生银行", "平安银行" to "平安银行",
            "兴业银行" to "兴业银行", "中信银行" to "中信银行", "广发银行" to "广发银行",
            "浦发银行" to "浦发银行", "华夏银行" to "华夏银行", "光大银行" to "光大银行",
        )
        for ((keyword, name) in bankKeywords) {
            if (text.contains(keyword)) return name
        }
        return null
    }

    private fun extractAmount(match: MatchResult): Int? {
        // 取最后一个捕获组（金额通常在最后）
        val amountStr = match.groupValues.lastOrNull { it.contains('.') || it.all { c -> c.isDigit() } }
            ?: return null
        val yuan = amountStr.toDoubleOrNull() ?: return null
        return (yuan * 100).roundToInt()
    }

    private fun extractCardLast4(match: MatchResult): String? {
        // 第一个 4 位纯数字捕获组通常是卡尾号
        return match.groupValues.firstOrNull { it.length == 4 && it.all { c -> c.isDigit() } }
    }

    private fun extractOrderId(text: String): String? {
        for (pattern in ORDER_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val id = match.groupValues.getOrNull(1)?.trim()
            if (!id.isNullOrBlank() && id.length in 4..32) return id
        }
        return null
    }
}
