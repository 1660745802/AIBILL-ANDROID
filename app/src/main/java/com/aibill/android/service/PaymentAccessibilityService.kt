package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
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
import javax.inject.Inject

/**
 * 支付页面无障碍识别服务（v3）
 *
 * 策略参考 iCost / AutoAccounting：
 * - 只在页面切换（TYPE_WINDOW_STATE_CHANGED）时触发（不监听内容变化）
 * - 严格三重条件：有"支付成功"关键词 + 有金额 + 无首页/聊天特征
 * - 提取简短摘要发 AI（不发全页面 800 字文本）
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

    /** 防抖：同内容 10s 内不重复 */
    private var lastHash: Int = 0
    private var lastTime: Long = 0L

    /** cooldown：同金额 N 分钟内只触发一次（防历史支付页面被重复识别） */
    private val recentAmounts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ═══════════════════════════════════════════════════════════════
    // 从云控规则读取（onServiceConnected 时初始化）
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
        private const val DEBOUNCE_MS = 10_000L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, A11yEntryPoint::class.java)
        notificationProcessor = entryPoint.notificationProcessor()
        appLogger = entryPoint.appLogger()
        rulesManager = entryPoint.rulesManager()

        // 从云控规则初始化
        loadRules()

        appLogger.info("A11Y", "无障碍服务已连接")
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

        // 每次事件实时读取最新规则（getRules()读内存缓存，无性能问题）
        loadRules()

        if (packageName !in paymentApps) return

        // 只在页面切换时触发（参考iCost/AutoAccounting做法）
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val rootNode = rootInActiveWindow ?: return

        try {
            // ===== 增强版全量页面日志 =====
            val className = ev.className?.toString() ?: "?"
            val shortClassName = className.substringAfterLast('.')
            val allTexts = mutableListOf<String>()
            collectAllNodeTexts(rootNode, allTexts)

            // 1. 基础页面快照（Activity类名 + 文本摘要）
            val pageSnapshot = allTexts.take(30).joinToString("|")
            appLogger.debug("A11Y_PAGE", "[$packageName/$shortClassName] $pageSnapshot")

            // 2. 完整Activity类名（用于建立白名单/黑名单）
            appLogger.debug("A11Y_META", "activity=$className pkg=$packageName texts=${allTexts.size} time=${System.currentTimeMillis()}")

            // 3. 节点结构详情（无条件记录，便于排查页面结构变化导致的漏识别）
            val nodeTree = buildNodeTree(rootNode, maxDepth = 5)
            appLogger.debug("A11Y_TREE", "[$shortClassName] $nodeTree")
            // ===== 全量日志结束 =====

            val hasPayKeyword = allTexts.any { text -> successKeywords.any { kw -> text.contains(kw) } }
            val hasAmount = allTexts.any { amountRegex.containsMatchIn(it) }

            // 判断是否为内嵌支付 App
            val isEmbeddedApp = packageName in embeddedPaymentApps

            // 条件1：有支付成功关键词
            // 内嵌 App 用更宽泛的关键词集合（它们的结果页文案不同于原生支付宝）
            val activeKeywords = if (isEmbeddedApp) successKeywords + embeddedSuccessKeywords else successKeywords
            val matchedKeyword = findMatchedKeyword(rootNode, activeKeywords)
            val hasSuccessKeyword = matchedKeyword != null

            if (!hasSuccessKeyword) {
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
                appLogger.debug("A11Y_SKIP", "有成功词无金额: [$shortClassName]")
                return
            }

            // 条件4：页面不能有历史日期（排除用户翻看历史账单）
            if (!isRecentPayment(rootNode)) {
                appLogger.debug("A11Y_SKIP", "历史日期拦截: [$shortClassName] amount=$amountText")
                return
            }

            // 三重条件全部满足 → 构建简短摘要
            val merchant = findMerchant(rootNode)
            val summary = if (merchant != null) {
                "支付成功 $amountText $merchant"
            } else {
                val context = collectNearbyText(rootNode, amountText)
                "支付成功 $amountText $context"
            }

            // 防抖
            val hash = summary.hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash && (now - lastTime) < DEBOUNCE_MS) {
                appLogger.debug("A11Y_SKIP", "防抖拦截(${DEBOUNCE_MS/1000}s内重复): $amountText")
                return
            }
            lastHash = hash
            lastTime = now

            // cooldown：同金额 N 分钟内只触发一次
            val lastAmountTime = recentAmounts[amountText]
            if (lastAmountTime != null && (now - lastAmountTime) < cooldownMs) {
                appLogger.debug("A11Y_SKIP", "cooldown拦截(${cooldownMs/1000/60}min内同金额): $amountText")
                return
            }
            recentAmounts[amountText] = now
            // 清理过期
            recentAmounts.entries.removeIf { now - it.value > cooldownMs }

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

    override fun onInterrupt() {
        appLogger.warn("A11Y", "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        appLogger.warn("A11Y", "无障碍服务销毁")
        serviceScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════

    private fun hasAnyKeyword(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        return findMatchedKeyword(root, keywords) != null
    }

    private fun findMatchedKeyword(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) return kw
        }
        return null
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
     */
    private fun isRecentPayment(root: AccessibilityNodeInfo): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(root, allTexts)
        val today = java.time.LocalDate.now()

        if (allTexts.any { it.contains("昨天") || it.contains("天前") || it.contains("月前") }) return false

        val datePattern = Regex("""(\d{1,2})月(\d{1,2})日""")
        for (text in allTexts) {
            val match = datePattern.find(text) ?: continue
            val month = match.groupValues[1].toIntOrNull() ?: continue
            val day = match.groupValues[2].toIntOrNull() ?: continue
            if (month != today.monthValue || day != today.dayOfMonth) return false
        }

        val dashDatePattern = Regex("""(\d{4})-(\d{2})-(\d{2})|(\d{2})-(\d{2})""")
        for (text in allTexts) {
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
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByPattern(child, pattern)
            if (result != null) {
    
                return result
            }

        }
        return null
    }
}
