package com.aibill.android.domain.a11y

import com.aibill.android.domain.notification.A11yProfileResolver
import java.math.RoundingMode

/**
 * A11Y 准入状态机 [A11yAdmissionGate] 需要的**纯文本判据**：无状态、无 Android 依赖、可 JVM 单测。
 *
 * 原先这些函数散在 `service/PaymentAccessibilityService` 与其 facade
 * `service/PaymentAccessibilityRecognition` 里：同一套判据有两个名字，且 facade 一半是
 * 真实现、一半是委托。现在**全部纯字符串判据**（页面身份 / root 归属 / 成功词 / 排除词 /
 * 金额 / 历史日期 / 重试锚点 / 列表页启发式）都归这里，facade 已删除；
 * platform 层只剩「操作 `AccessibilityNodeInfo` 树」的部分（遍历 / 摘取 / 调度）。
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

    // ─────────────────────────────────────────────────────────────
    // 页面身份 / root 归属 / 成功词 / 排除词
    //（原 `service/PaymentAccessibilityRecognition` 的真实现部分；纯字符串判据，无 Android 依赖）
    // ─────────────────────────────────────────────────────────────

    /** 页面身份 token：包名 + windowId + 类名（都规范化小写）。 */
    fun pageToken(packageName: String, className: String, windowId: Int): String =
        "${packageName.trim().lowercase()}|$windowId|${className.trim().lowercase()}"

    /** `(activity 类名, pageToken)`，供 CONTENT 事件在没有 STATE 时回退。 */
    fun pageState(packageName: String, className: String, windowId: Int): Pair<String, String> =
        className to pageToken(packageName, className, windowId)

    /** root 是否属于本次事件：**仅跳包才拒绝**，刻意不比较 windowId。 */
    fun isRootForEvent(rootPackage: String?, eventPackage: String): Boolean =
        rootPackage == eventPackage

    /**
     * 成功词**精确**匹配（系统 `findByText` 是模糊匹配，需二次校验）。
     * 允许「了」「空白」「标点」这类无害后缀，拒绍「率」「查看…的订单」这类语义后缀。
     */
    fun isSuccessKeywordMatch(text: String, keyword: String): Boolean {
        val value = text.trim()
        if (value == keyword) return true
        if (value.length > keyword.length * 3 || !value.contains(keyword)) return false
        val suffix = value.substring(value.indexOf(keyword) + keyword.length)
        if (suffix.isBlank()) return true
        if (suffix == "了" || suffix.startsWith("了 ") || suffix.startsWith("了，") ||
            suffix.startsWith("了。") || suffix.startsWith("了！")
        ) {
            return true
        }
        return suffix.first().isWhitespace() || suffix.first() in "!！。,.，:：¥￥(（[【"
    }

    /** 参与 A11Y 监听的支付类包：微信/支付宝 + 云控 `a11y.embedded_payment_apps`。 */
    fun paymentPackages(embeddedPackages: List<String>): Set<String> =
        setOf("com.tencent.mm", "com.eg.android.AlipayGphone") +
            embeddedPackages.asSequence().map(String::trim).filter(String::isNotBlank).toSet()

    /** 排除词命中：任一非空排除词是任一文本的子串。 */
    fun containsExcludeKeyword(texts: List<String>, keywords: List<String>): Boolean =
        keywords.any { keyword -> keyword.isNotBlank() && texts.any { it.contains(keyword) } }

    // ─────────────────────────────────────────────────────────────
    // 列表页启发式（页面级结构判据；调用方在下游预筛 NotificationPrefilterImpl）
    // ─────────────────────────────────────────────────────────────

    /** 列表页「金额节点」数下限：真实支付结果页最多 2 个（实付 + 原价/优惠）。 */
    const val LIST_PAGE_MIN_AMOUNTS = 3

    /**
     * 列表词必须出现在 `pageText` 前 N 个字符内（页面标题区）。
     * 真交易结果页常有 ≥3 个金额、底部又有「查看账单」入口——只看「金额≥3 且命中列表词」
     * 会把它们当列表页拦掉（P0 误杀）。取舍：宁可漏挡，不可误杀。
     * 可被云控 `a11y.app_profiles.<pkg>.list_page_head_chars` 覆盖。
     */
    const val LIST_PAGE_KEYWORD_HEAD_CHARS = A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS

    /** 列表页启发式的内置兜底列表词（7 个）。生产取值云控优先、内置兜底。 */
    val DEFAULT_LIST_KEYWORDS = listOf("账单", "交易记录", "明细", "全部交易", "收支记录", "历史", "月账单")

    /**
     * 列表页金额计数专用正则：覆盖 `¥12.00` / `12.00元` 与 `1,000元` 千分位。
     * 与云控 `a11y.amount_regex` 解耦——这是纯客户端页面结构判据。
     */
    val LIST_PAGE_AMOUNT_REGEX = Regex("""[¥￥][ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*\d[\d,]*(?:\.\d{1,2})?|\d[\d,]*(?:\.\d{1,2})?[ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*元""")

    /** 统计一组文本里像金额的出现次数（同一条文本里有多个则累计）。 */
    fun countAmountLikeTexts(texts: List<String>, amountRegex: Regex): Int =
        texts.sumOf { text -> amountRegex.findAll(text).count() }

    /**
     * 列表页启发式：金额数 ≥ [minAmounts] 且列表词出现在页面前 [LIST_PAGE_KEYWORD_HEAD_CHARS]
     * 个字符内（标题区）→ 判为账单/明细页。
     */
    fun looksLikeListPage(
        pageText: String,
        amountRegex: Regex,
        listKeywords: List<String>,
        minAmounts: Int = LIST_PAGE_MIN_AMOUNTS,
        headChars: Int = LIST_PAGE_KEYWORD_HEAD_CHARS,
    ): Boolean {
        val head = pageText.take(headChars)
        if (listKeywords.none { it.isNotBlank() && head.contains(it) }) return false
        return countAmountLikeTexts(listOf(pageText), amountRegex) >= minAmounts
    }
}
