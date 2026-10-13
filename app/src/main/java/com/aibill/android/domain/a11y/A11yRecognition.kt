package com.aibill.android.domain.a11y

import java.math.RoundingMode

/**
 * A11Y 准入状态机 [A11yAdmissionGate] 需要的**纯文本判据**：无状态、无 Android 依赖、可 JVM 单测。
 *
 * C01 收口：这些函数原先私有在 `service/PaymentAccessibilityService` 里。gate 在 domain 层，
 * 不能反向依赖 service，因此搬到这里。留在 platform 的是「树上的平台事实」——
 * `pageToken` / `isRootForEvent` / `findMatchedKeyword`（成功词精确匹配）/ 排除词包含 /
 * 列表页启发式（其调用方在下游预筛）。
 *
 * `service/PaymentAccessibilityRecognition` 保留原签名并**委托**到这里，避免两份实现。
 */
internal object A11yRecognition {

    private const val HISTORY_NEIGHBOR = 5
    private const val HISTORY_HEAD_FALLBACK = 15
    private const val NEARBY_CONTEXT = 3
    private const val PAGE_TEXT_MAX_NODES = 60
    private const val PAGE_TEXT_MAX_CHARS = 500

    /** 金额文本 → 整数分（如 "¥12.50" → "1250"）。解析不出返回 null。 */
    fun amountKey(amountText: String): String? {
        val value = amountText
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
            .toBigDecimalOrNull() ?: return null
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigInteger().toString()
    }

    /** 历史日期判据：`texts` 全是「今天」才返回 true。 */
    fun isRecentTextRange(texts: List<String>, today: java.time.LocalDate): Boolean {
        if (texts.any { it.contains("昨天") || it.contains("昨日") || it.contains("天前") || it.contains("月前") }) {
            return false
        }
        val monthDay = Regex("""(\d{1,2})月(\d{1,2})日""")
        // 裸 M-D 串常是订单号；只接受完整 ISO 日期。
        val dashDate = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
        return texts.none { text ->
            val chinese = monthDay.find(text)
            if (chinese != null) {
                chinese.groupValues[1].toIntOrNull() != today.monthValue ||
                    chinese.groupValues[2].toIntOrNull() != today.dayOfMonth
            } else {
                val dashed = dashDate.find(text)
                dashed != null && (
                    dashed.groupValues[2].toIntOrNull() != today.monthValue ||
                        dashed.groupValues[3].toIntOrNull() != today.dayOfMonth
                    )
            }
        }
    }

    /**
     * 历史日期判据的**切片窗**：只检查金额节点附近（±5）的文本；
     * 定位不到金额时看前 15 个节点（支付结果通常在页面上方）。
     */
    fun isRecentPayment(allTexts: List<String>, amountText: String, today: java.time.LocalDate): Boolean {
        val amountClean = amountText.replace("¥", "").replace("￥", "")
        val amountIdx = allTexts.indexOfFirst { it.contains(amountClean) }
        val checkRange = if (amountIdx >= 0) {
            val start = (amountIdx - HISTORY_NEIGHBOR).coerceAtLeast(0)
            val end = (amountIdx + HISTORY_NEIGHBOR).coerceAtMost(allTexts.size - 1)
            allTexts.subList(start, end + 1)
        } else {
            allTexts.take(HISTORY_HEAD_FALLBACK)
        }
        return isRecentTextRange(checkRange, today)
    }

    /** 重试锚点：同一容器里稳定的文本（去掉成功词与金额）。 */
    fun retryAnchor(texts: List<String>, successKeyword: String): Set<String> =
        texts.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it.length <= 50 }
            .filterNot { it == successKeyword || amountKey(it) != null }
            .distinct()
            .take(6)
            .toSet()

    fun matchesRetryAnchor(anchor: Set<String>, currentTexts: List<String>): Boolean {
        if (anchor.isEmpty()) return false
        val current = currentTexts.mapTo(HashSet(), String::trim)
        return anchor.count(current::contains) >= minOf(2, anchor.size)
    }

    /** summary 无商家时的邻近上下文（金额前后各 3 个节点）。 */
    fun collectNearbyText(allTexts: List<String>, amountText: String): String {
        val amountIdx = allTexts.indexOfFirst { it.contains(amountText.replace("¥", "").replace("￥", "")) }
        if (amountIdx < 0) return allTexts.take(5).joinToString(" ")
        val start = (amountIdx - NEARBY_CONTEXT).coerceAtLeast(0)
        val end = (amountIdx + NEARBY_CONTEXT).coerceAtMost(allTexts.size - 1)
        return allTexts.subList(start, end + 1)
            .filter { it != amountText && it.length in 2..30 }
            .joinToString(" ")
            .take(80)
    }

    /**
     * A11Y 专用：整页文本去噪后拼成 `Capture.pageText`（供预筛判列表页，不参与 AI 输入）。
     * 去噪刻意简单：丢长度 <2、纯数字/纯符号项，distinct，最多 60 节点 / 500 字。
     */
    fun buildPageText(allTexts: List<String>): String =
        allTexts.asSequence()
            .map(String::trim)
            .filter { it.length >= 2 }
            .filterNot(::isNoiseText)
            .distinct()
            .take(PAGE_TEXT_MAX_NODES)
            .joinToString(" ")
            .take(PAGE_TEXT_MAX_CHARS)

    /** 纯数字（`32.50` / `1,000`）或纯符号（`¥` / `——`）对判「列表页」无信息量。 */
    fun isNoiseText(text: String): Boolean {
        val pureNumber = text.all { it.isDigit() || it == '.' || it == ',' }
        val pureSymbol = text.none { it.isLetterOrDigit() }
        return pureNumber || pureSymbol
    }
}
