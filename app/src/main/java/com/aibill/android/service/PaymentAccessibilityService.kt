package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 支付页面无障碍识别服务（v4）
 *
 * v3 → v4 改进：
 * 1. 同时监听 TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOW_CONTENT_CHANGED
 *    - STATE_CHANGED：立即处理（Activity 切换）
 *    - CONTENT_CHANGED：3s 节流（同 Activity 内 Fragment/WebView 页面更新）
 * 2. 延迟重试：首次有成功关键词但无金额时，500ms 后再试一次（解决渐进渲染）
 * 3. cooldown 去重改为"金额+商家"组合键（避免同金额不同商家被误杀）
 * 4. isRecentPayment 只检查金额节点附近的日期文本（避免页面无关文本触发误杀）
 */
class PaymentAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface A11yEntryPoint {
        fun notificationProcessor(): NotificationProcessor
        fun appLogger(): com.aibill.android.util.AppLogger
        fun rulesManager(): NotificationRulesManager
    }

    private lateinit var notificationProcessor: NotificationProcessor
    private lateinit var appLogger: com.aibill.android.util.AppLogger
    private lateinit var rulesManager: NotificationRulesManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 防抖：同内容 8s 内不重复（从 10s 降低以减少快速支付场景的遗漏） */
    private var lastHash: Int = 0
    private var lastTime: Long = 0L

    /** cooldown：同"金额+商家"组合 N 分钟内只触发一次 */
    private val recentPayments = ConcurrentHashMap<String, Long>()

    /** CONTENT_CHANGED 节流：同包名 3s 内只处理一次 */
    private val contentChangeThrottle = ConcurrentHashMap<String, Long>()

    /** 延迟重试标记：避免同一个页面重复调度重试 */
    private var pendingRetryHash: Int = 0

    // ═══════════════════════════════════════════════════════════════
    // 从云控规则读取（每次事件时从内存缓存刷新）
    // ═══════════════════════════════════════════════════════════════
    private var embeddedPaymentApps: Set<String> = emptySet()
    private var paymentApps: Set<String> = emptySet()
    private var successKeywords: List<String> = emptyList()
    private var embeddedSuccessKeywords: List<String> = emptyList()
    private var commonExcludeKeywords: List<String> = emptyList()
    private var wechatAlipayExcludeKeywords: List<String> = emptyList()
    private var amountRegex: Regex = Regex("""[¥￥]\s*(\d+\.?\d{0,2})|(\d+\.?\d{0,2})元""")
    private var cooldownMs: Long = 5 * 60 * 1000L

    companion object {
        private const val DEBOUNCE_MS = 8_000L
        private const val CONTENT_CHANGE_THROTTLE_MS = 3_000L
        private const val RETRY_DELAY_MS = 500L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, A11yEntryPoint::class.java)
        notificationProcessor = entryPoint.notificationProcessor()
        appLogger = entryPoint.appLogger()
        rulesManager = entryPoint.rulesManager()

        loadRules()
        appLogger.info("A11Y", "无障碍服务已连接 (v4: STATE+CONTENT, 节流+重试)")
    }

    private fun loadRules() {
        val rules = rulesManager.getRules()
        embeddedPaymentApps = rules.a11y.embeddedPaymentApps.toSet()
        paymentApps = setOf(PACKAGE_WECHAT, PACKAGE_ALIPAY) + embeddedPaymentApps
        successKeywords = rules.a11y.successKeywords
        embeddedSuccessKeywords = rules.a11y.embeddedSuccessKeywords
        commonExcludeKeywords = rules.a11y.commonExcludeKeywords
        wechatAlipayExcludeKeywords = rules.a11y.wechatAlipayExcludeKeywords
        amountRegex = try {
            Regex(rules.a11y.amountRegex)
        } catch (e: Exception) {
            Regex("""[¥￥]\s*(\d+\.?\d{0,2})|(\d+\.?\d{0,2})元""")
        }
        cooldownMs = rules.a11y.cooldownMinutes * 60 * 1000L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // 实时读取最新规则（getRules() 读内存缓存，无性能问题）
        loadRules()

        if (packageName !in paymentApps) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Activity 切换 → 记录当前 Activity 并处理
                val className = ev.className?.toString() ?: ""
                if (isActivityClassName(className)) {
                    currentActivity[packageName] = className
                }
                processPaymentCheck(packageName, ev, isRetry = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // CONTENT_CHANGED 防误触：只有在上一次 STATE_CHANGED 记录的 Activity
                // 命中"可能的支付结果页"特征时才处理（参考反编译项目的 Activity 白名单策略）
                val activity = currentActivity[packageName] ?: return
                if (!isPotentialPaymentActivity(activity, packageName)) return

                val now = System.currentTimeMillis()
                val lastCheck = contentChangeThrottle[packageName] ?: 0L
                if (now - lastCheck < CONTENT_CHANGE_THROTTLE_MS) return
                contentChangeThrottle[packageName] = now
                processPaymentCheck(packageName, ev, isRetry = false)
            }
        }
    }

    /** 记录每个包名当前所在的 Activity */
    private val currentActivity = ConcurrentHashMap<String, String>()

    /**
     * 判断类名是否是一个 Activity（而非 View/Dialog 等）。
     * 参考反编译项目：只有包含 activity/home/welcome/appbrandui 等特征才算。
     */
    private fun isActivityClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val lower = className.lowercase()
        // 系统 View 类不算 Activity
        if (lower.startsWith("android.widget.") || lower.startsWith("android.view.")) return false
        // 常见 Activity 特征
        return lower.contains("activity") || lower.contains("fragment")
                || lower.contains("home") || lower.contains("appbrandui")
                || lower.contains("welcome") || lower.contains("launcher")
                || lower.contains("main") || lower.contains("page")
    }

    /**
     * 判断当前 Activity 是否"可能是支付结果页"——只在这些页面上才响应 CONTENT_CHANGED。
     *
     * 策略（借鉴反编译项目的 Activity 白名单思路，但用模糊匹配而非硬编码）：
     * - 微信/支付宝：几乎所有 Activity 都可能展示支付结果（小程序容器等）
     * - 内嵌 App（美团/饿了么等）：只有包含 pay/order/result/cashier/success 等特征的 Activity
     */
    private fun isPotentialPaymentActivity(activity: String, packageName: String): Boolean {
        // 微信和支付宝的支付结果可能出现在任何容器页面
        if (packageName == PACKAGE_WECHAT || packageName == PACKAGE_ALIPAY) return true

        // 内嵌支付 App：只在可能的支付/订单页面上响应 CONTENT_CHANGED
        val lower = activity.lowercase()
        return PAYMENT_ACTIVITY_HINTS.any { lower.contains(it) }
    }

    /** 内嵌 App 中可能是支付结果页的 Activity 类名关键词 */
    private val PAYMENT_ACTIVITY_HINTS = listOf(
        "pay", "payment", "cashier", "order", "result", "success",
        "trade", "checkout", "confirm", "receipt", "bill", "transaction"
    )

    /**
     * 核心识别逻辑。
     * @param isRetry 是否是延迟重试调用
     */
    private fun processPaymentCheck(packageName: String, ev: AccessibilityEvent, isRetry: Boolean) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val className = ev.className?.toString() ?: "?"
            val shortClassName = className.substringAfterLast('.')

            // ===== 全量页面日志（用于排查） =====
            val allTexts = mutableListOf<String>()
            collectAllNodeTexts(rootNode, allTexts)

            val pageSnapshot = allTexts.take(30).joinToString("|")
            appLogger.debug("A11Y_PAGE", "[$packageName/$shortClassName] $pageSnapshot")
            appLogger.debug("A11Y_META", "activity=$className pkg=$packageName texts=${allTexts.size} retry=$isRetry time=${System.currentTimeMillis()}")

            val nodeTree = buildNodeTree(rootNode, maxDepth = 5)
            appLogger.debug("A11Y_TREE", "[$shortClassName] $nodeTree")
            // ===== 日志结束 =====

            // 判断是否为内嵌支付 App
            val isEmbeddedApp = packageName in embeddedPaymentApps
            val activeKeywords = if (isEmbeddedApp) successKeywords + embeddedSuccessKeywords else successKeywords

            // 条件1：有支付成功关键词
            val matchedKeyword = findMatchedKeyword(rootNode, activeKeywords)
            if (matchedKeyword == null) {
                val hasAmount = allTexts.any { amountRegex.containsMatchIn(it) }
                if (hasAmount) {
                    appLogger.debug("A11Y_MISS", "有金额无成功词: [$shortClassName] ${allTexts.take(15).joinToString("|")}")
                }
                return
            }

            // 条件2：排除非支付结果页
            val activeExcludeKeywords = if (isEmbeddedApp) commonExcludeKeywords else wechatAlipayExcludeKeywords + commonExcludeKeywords
            if (hasAnyKeyword(rootNode, activeExcludeKeywords)) {
                appLogger.debug("A11Y_SKIP", "排除词命中: [$shortClassName] pkg=$packageName")
                return
            }

            // 条件3：有金额文字
            val amountText = findAmount(rootNode)
            if (amountText == null) {
                // 有成功词但没找到金额 → 可能页面还没渲染完
                if (!isRetry) {
                    scheduleRetry(packageName, ev)
                } else {
                    appLogger.debug("A11Y_SKIP", "重试后仍无金额: [$shortClassName]")
                }
                return
            }

            // 条件3.5：金额合理性校验（排除 ¥0.00、超大金额等异常值）
            val amountValue = extractNumericAmount(amountText)
            if (amountValue == null || amountValue < 0.01 || amountValue > 100_000.0) {
                appLogger.debug("A11Y_SKIP", "金额异常($amountValue): [$shortClassName] raw=$amountText")
                return
            }

            // 条件4：不是历史账单（只检查金额节点附近文本）
            if (!isRecentPayment(rootNode, amountText)) {
                appLogger.debug("A11Y_SKIP", "历史日期拦截: [$shortClassName] amount=$amountText")
                return
            }

            // ===== 三重条件全部满足 → 构建摘要 =====
            val merchant = findMerchant(rootNode)
            val summary = if (merchant != null) {
                "支付成功 $amountText $merchant"
            } else {
                val context = collectNearbyText(rootNode, amountText)
                "支付成功 $amountText $context"
            }

            // 防抖：同内容 8s 内不重复
            val hash = summary.hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash && (now - lastTime) < DEBOUNCE_MS) {
                appLogger.debug("A11Y_SKIP", "防抖拦截(${DEBOUNCE_MS / 1000}s): $amountText")
                return
            }
            lastHash = hash
            lastTime = now

            // cooldown：同"金额+商家"组合 N 分钟内只触发一次
            val deduKey = "$amountText|${merchant ?: ""}"
            val lastPayTime = recentPayments[deduKey]
            if (lastPayTime != null && (now - lastPayTime) < cooldownMs) {
                appLogger.debug("A11Y_SKIP", "cooldown拦截(${cooldownMs / 1000 / 60}min同金额+商家): $deduKey")
                return
            }
            recentPayments[deduKey] = now
            // 清理过期条目
            recentPayments.entries.removeIf { now - it.value > cooldownMs }

            appLogger.info("A11Y", "✓识别支付页: $summary pkg=$packageName keyword=$matchedKeyword")

            // 交给 Processor（和通知渠道统一处理）
            serviceScope.launch {
                notificationProcessor.process(
                    NotificationProcessor.Item(
                        packageName = packageName,
                        title = "支付成功",
                        fullText = summary,
                        channel = NotificationProcessor.Channel.A11Y,
                    )
                )
            }
        } finally {
            // rootNode 不再需要手动 recycle (Android 14+ deprecated)
        }
    }

    /**
     * 延迟重试：500ms 后重新获取 rootNode 再解析一次。
     * 解决支付结果页渐进渲染导致首次扫描时金额还未出现的问题。
     */
    private fun scheduleRetry(packageName: String, ev: AccessibilityEvent) {
        val retryHash = "$packageName|${ev.className}".hashCode()
        if (retryHash == pendingRetryHash) return // 同页面已有挂起的重试
        pendingRetryHash = retryHash

        appLogger.debug("A11Y_RETRY", "调度延迟重试: pkg=$packageName class=${ev.className}")

        handler.postDelayed({
            pendingRetryHash = 0
            val retryRoot = rootInActiveWindow ?: return@postDelayed
            // 重新检查当前窗口是否还在同一个 app
            val currentPkg = retryRoot.packageName?.toString()
            if (currentPkg == packageName) {
                processPaymentCheck(packageName, ev, isRetry = true)
            }
        }, RETRY_DELAY_MS)
    }

    override fun onInterrupt() {
        appLogger.warn("A11Y", "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        appLogger.warn("A11Y", "无障碍服务销毁")
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private fun hasAnyKeyword(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        return findMatchedKeyword(root, keywords) != null
    }

    /**
     * 查找匹配的关键词。
     *
     * findAccessibilityNodeInfosByText 是模糊匹配（系统 API 行为），
     * 所以需要二次验证：节点文本必须**精确包含**关键词，
     * 且关键词不能只是更长文本的一部分（如 "支付成功率" 不应匹配 "支付成功"）。
     *
     * 借鉴反编译项目：它对每个 findByText 结果都做了 getText().equals(str) 的二次校验。
     */
    private fun findMatchedKeyword(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                // 精确匹配：节点文本等于关键词，或文本短于关键词的 3 倍（避免在长段落中误命中）
                if (isExactKeywordMatch(text, kw) || isExactKeywordMatch(desc, kw)) {
                    return kw
                }
            }
        }
        return null
    }

    /**
     * 精确关键词匹配：
     * - 文本等于关键词 → 匹配
     * - 文本很短（<= 关键词长度 * 3）且包含关键词 → 匹配（如 "支付成功！"）
     * - 文本很长（如整段描述）且包含关键词 → 不匹配（避免 "查看支付成功的订单" 误触发）
     */
    private fun isExactKeywordMatch(text: String, keyword: String): Boolean {
        if (text.isBlank()) return false
        if (text == keyword) return true
        if (text.length <= keyword.length * 3 && text.contains(keyword)) {
            // 额外检查：关键词后面不能跟"率"、"的"、"后"等使语义改变的字
            val idx = text.indexOf(keyword)
            val afterIdx = idx + keyword.length
            if (afterIdx < text.length) {
                val nextChar = text[afterIdx]
                if (nextChar in "率的后了吗么呢页面记录") return false
            }
            return true
        }
        return false
    }

    /** 从金额文本中提取数值（如 "¥12.50" → 12.5） */
    private fun extractNumericAmount(amountText: String): Double? {
        val cleaned = amountText.replace("¥", "").replace("￥", "").replace("元", "").replace(" ", "").trim()
        return cleaned.toDoubleOrNull()
    }

    /** 在节点树中找第一个匹配金额格式的文本 */
    private fun findAmount(root: AccessibilityNodeInfo): String? {
        return findTextByPattern(root, amountRegex)
    }

    /** 查找商家名：找"收款方/商户/付款给"附近的文字 */
    private fun findMerchant(root: AccessibilityNodeInfo): String? {
        val nativeLabels = listOf("收款方", "商户", "商家", "付款给")
        val orderLabels = listOf("店铺", "商家名", "卖家", "店名", "商品")

        val allLabels = nativeLabels + orderLabels
        for (label in allLabels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val parent = node.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        val text = child.text?.toString()?.trim()
                        if (!text.isNullOrBlank() && text != label && text.length in 2..30 && !text.contains("¥")) {
                            return text
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 判断页面是否是"刚刚发生的支付"而非"历史账单"。
     *
     * v4 改进：只检查金额节点附近（上下 5 个节点）的日期文本，
     * 避免页面底部/顶部无关文本（如 "昨天 13:00 发货"）触发误杀。
     */
    private fun isRecentPayment(root: AccessibilityNodeInfo, amountText: String): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(root, allTexts)

        // 找到金额所在位置，只检查附近范围
        val amountClean = amountText.replace("¥", "").replace("￥", "")
        val amountIdx = allTexts.indexOfFirst { it.contains(amountClean) }
        val checkRange = if (amountIdx >= 0) {
            val start = (amountIdx - 5).coerceAtLeast(0)
            val end = (amountIdx + 5).coerceAtMost(allTexts.size - 1)
            allTexts.subList(start, end + 1)
        } else {
            // 找不到金额位置时，只检查前 15 个节点（支付结果通常在页面上方）
            allTexts.take(15)
        }

        val today = java.time.LocalDate.now()

        // 检查明确的过去时间标记
        if (checkRange.any { it.contains("天前") || it.contains("月前") }) return false

        // "昨天" 需更谨慎：只在它看起来是时间标签时才拦截（如 "昨天 14:30"）
        val yesterdayTimePattern = Regex("""昨天\s*\d{1,2}:\d{2}""")
        if (checkRange.any { yesterdayTimePattern.containsMatchIn(it) }) return false

        // 检查 "X月X日" 格式
        val datePattern = Regex("""(\d{1,2})月(\d{1,2})日""")
        for (text in checkRange) {
            val match = datePattern.find(text) ?: continue
            val month = match.groupValues[1].toIntOrNull() ?: continue
            val day = match.groupValues[2].toIntOrNull() ?: continue
            if (month != today.monthValue || day != today.dayOfMonth) return false
        }

        // 检查 "YYYY-MM-DD" 或 "MM-DD" 格式
        val dashDatePattern = Regex("""(\d{4})-(\d{2})-(\d{2})|(\d{2})-(\d{2})""")
        for (text in checkRange) {
            val match = dashDatePattern.find(text) ?: continue
            val month = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: continue
            val day = (match.groupValues[3].ifEmpty { match.groupValues[5] }).toIntOrNull() ?: continue
            if (month != today.monthValue || day != today.dayOfMonth) return false
        }

        return true
    }

    /**
     * 收集金额节点附近的文本作为上下文。
     */
    private fun collectNearbyText(root: AccessibilityNodeInfo, amountText: String): String {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(root, allTexts)
        val amountIdx = allTexts.indexOfFirst { it.contains(amountText.replace("¥", "").replace("￥", "")) }
        if (amountIdx < 0) return allTexts.take(5).joinToString(" ")
        val start = (amountIdx - 3).coerceAtLeast(0)
        val end = (amountIdx + 3).coerceAtMost(allTexts.size - 1)
        return allTexts.subList(start, end + 1)
            .filter { it != amountText && it.length in 2..30 }
            .joinToString(" ")
            .take(80)
    }

    private fun collectAllNodeTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length in 1..50) result.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length in 1..50 && desc != text) result.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodeTexts(child, result)
        }
    }

    /**
     * 构建节点树摘要字符串（用于日志分析页面结构）。
     */
    private fun buildNodeTree(node: AccessibilityNodeInfo, maxDepth: Int, depth: Int = 0): String {
        if (depth > maxDepth) return ""
        val sb = StringBuilder()
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val text = node.text?.toString()?.trim()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.take(30) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""

        if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank()) {
            sb.append("L$depth:")
            if (viewId.isNotBlank()) sb.append("[$viewId]")
            if (text.isNotBlank()) sb.append("\"$text\"")
            if (desc.isNotBlank()) sb.append("{$desc}")
            sb.append("($cls)")
            sb.append("|")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(buildNodeTree(child, maxDepth, depth + 1))
        }
        return if (sb.length > 500) sb.substring(0, 500) + "..." else sb.toString()
    }

    /** 递归查找匹配正则的节点文本 */
    private fun findTextByPattern(node: AccessibilityNodeInfo, pattern: Regex): String? {
        val text = node.text?.toString()
        if (text != null) {
            val match = pattern.find(text)
            if (match != null) return match.value
        }
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            val match = pattern.find(desc)
            if (match != null) return match.value
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByPattern(child, pattern)
            if (result != null) return result
        }
        return null
    }
}
