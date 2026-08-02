package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
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
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var notificationProcessor: NotificationProcessor
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 防抖：同内容 10s 内不重复 */
    private var lastHash: Int = 0
    private var lastTime: Long = 0L

    /** cooldown：同金额 5 分钟内只触发一次（防历史支付页面被重复识别） */
    private val recentAmounts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val COOLDOWN_MS = 5 * 60 * 1000L

    companion object {
        private const val DEBOUNCE_MS = 10_000L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        /** 内嵌支付宝的第三方 App（支付流程在其内部完成） */
        private val EMBEDDED_PAYMENT_APPS = setOf(
            "me.ele",                          // 饿了么
            "com.sankuai.meituan",             // 美团
            "com.dianping.v1",                 // 大众点评
            "com.taobao.taobao",               // 淘宝
            "com.tmall.wireless",              // 天猫
            "com.xunmeng.pinduoduo",           // 拼多多
            "cn.damai",                        // 大麦
            "com.taobao.idlefish",             // 闲鱼
            "com.autonavi.minimap",            // 高德地图
        )

        /** 所有需要监听的 App */
        private val PAYMENT_APPS = setOf(PACKAGE_WECHAT, PACKAGE_ALIPAY) + EMBEDDED_PAYMENT_APPS

        /** 支付成功关键词（微信/支付宝原生支付结果页） */
        private val SUCCESS_KEYWORDS = listOf("支付成功", "付款成功", "交易成功", "支付完成")

        /** 内嵌支付场景的成功关键词（订单结果页/支付确认页） */
        private val EMBEDDED_SUCCESS_KEYWORDS = listOf(
            "已付款", "订单支付成功",
            "等待商家接单", "订单已提交",
            "已支付", "实付",
        )

        /** 首页/聊天列表特征词——仅用于微信/支付宝（排除误触发） */
        private val WECHAT_ALIPAY_EXCLUDE_KEYWORDS = listOf(
            "朋友圈", "通讯录", "发现", "搜索小程序", "扫一扫",
            "视频号", "看一看", "摇一摇", "附近", "小程序面板",
        )

        /** 通用排除词——所有 App 共用（明确不是支付结果的页面） */
        private val COMMON_EXCLUDE_KEYWORDS = listOf(
            "购物车", "加入购物车", "立即购买", "去支付", "确认订单",
            "极速付款", "立即付款", "确认付款", "更改付款方式",
        )

        /** 金额正则：匹配 ¥xx.xx / ￥xx.xx / xx.xx元 / xx元 */
        private val AMOUNT_REGEX = Regex("""[¥￥]\s*(\d+\.?\d{0,2})|(\d+\.?\d{0,2})元""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLogger.info("A11Y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        if (packageName !in PAYMENT_APPS) return

        // 只在页面切换时触发（参考iCost/AutoAccounting做法）
        // 支付结果页是新页面 → STATE_CHANGED 能覆盖
        // 不监听 CONTENT_CHANGED → 避免频繁遍历节点树（每秒几百次）
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

            val hasPayKeyword = allTexts.any { text -> SUCCESS_KEYWORDS.any { kw -> text.contains(kw) } }
            val hasAmount = allTexts.any { AMOUNT_REGEX.containsMatchIn(it) }

            // 判断是否为内嵌支付 App
            val isEmbeddedApp = packageName in EMBEDDED_PAYMENT_APPS

            // 条件1：有支付成功关键词
            // 内嵌 App 用更宽泛的关键词集合（它们的结果页文案不同于原生支付宝）
            val activeKeywords = if (isEmbeddedApp) SUCCESS_KEYWORDS + EMBEDDED_SUCCESS_KEYWORDS else SUCCESS_KEYWORDS
            val hasSuccessKeyword = hasAnyKeyword(rootNode, activeKeywords)

            if (!hasSuccessKeyword) {
                if (hasAmount) {
                    appLogger.debug("A11Y_MISS", "有金额无成功词: [$shortClassName] ${allTexts.take(15).joinToString("|")}")
                }
                return
            }

            // 条件2：排除非支付结果页
            // 微信/支付宝用完整排除词；内嵌 App 只用通用排除词（它们没有朋友圈等特征）
            val activeExcludeKeywords = if (isEmbeddedApp) COMMON_EXCLUDE_KEYWORDS else WECHAT_ALIPAY_EXCLUDE_KEYWORDS + COMMON_EXCLUDE_KEYWORDS
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
                // 商家名没找到，收集金额附近的上下文文本给 AI 判断
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

            // cooldown：同金额 5 分钟内只触发一次
            val lastAmountTime = recentAmounts[amountText]
            if (lastAmountTime != null && (now - lastAmountTime) < COOLDOWN_MS) {
                appLogger.debug("A11Y_SKIP", "cooldown拦截(${COOLDOWN_MS/1000/60}min内同金额): $amountText")
                return
            }
            recentAmounts[amountText] = now
            // 清理过期
            recentAmounts.entries.removeIf { now - it.value > COOLDOWN_MS }

            appLogger.info("A11Y", "✓识别支付页: $summary pkg=$packageName")

            // 交给 Processor（和通知渠道统一处理）
            serviceScope.launch {
                notificationProcessor.process(
                    NotificationProcessor.Item(
                        packageName = packageName,
                        title = "支付成功",
                        fullText = summary, // 简短摘要，不是全页面文字
                        channel = NotificationProcessor.Channel.A11Y,
                    )
                )
            }
        } finally {
            rootNode.recycle()
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
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /** 在节点树中找第一个匹配 ¥XX.XX 格式的文本 */
    private fun findAmount(root: AccessibilityNodeInfo): String? {
        return findTextByPattern(root, AMOUNT_REGEX)
    }

    /** 查找商家名：找"收款方/商户/付款给"附近的文字 */
    private fun findMerchant(root: AccessibilityNodeInfo): String? {
        // 原生支付宝/微信标签
        val nativeLabels = listOf("收款方", "商户", "商家", "付款给")
        // 内嵌支付 App 订单页标签（饿了么/美团/淘宝等）
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
                            child.recycle()
                            parent.recycle()
                            node.recycle()
                            return text
                        }
                        child.recycle()
                    }
                    parent.recycle()
                }
                node.recycle()
            }
        }
        return null
    }

    /**
     * 判断页面是否是"刚刚发生的支付"而非"历史账单"。
     * 检查节点树中是否有非今天的日期——有就说明是旧账单，不处理。
     */
    private fun isRecentPayment(root: AccessibilityNodeInfo): Boolean {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(root, allTexts)
        val today = java.time.LocalDate.now()

        // "昨天"/"X天前"/"X月前" → 历史账单
        if (allTexts.any { it.contains("昨天") || it.contains("天前") || it.contains("月前") }) return false

        // 检查"X月X日"格式，如果不是今天 → 历史
        val datePattern = Regex("""(\d{1,2})月(\d{1,2})日""")
        for (text in allTexts) {
            val match = datePattern.find(text) ?: continue
            val month = match.groupValues[1].toIntOrNull() ?: continue
            val day = match.groupValues[2].toIntOrNull() ?: continue
            if (month != today.monthValue || day != today.dayOfMonth) return false
        }

        // 检查"YYYY-MM-DD"或"MM-DD"格式
        val dashDatePattern = Regex("""(\d{4})-(\d{2})-(\d{2})|(\d{2})-(\d{2})""")
        for (text in allTexts) {
            val match = dashDatePattern.find(text) ?: continue
            val month = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: continue
            val day = (match.groupValues[3].ifEmpty { match.groupValues[5] }).toIntOrNull() ?: continue
            if (month != today.monthValue || day != today.dayOfMonth) return false
        }

        return true // 没有历史日期特征 → 认为是当前支付
    }

    /**
     * 收集金额节点附近的文本作为上下文（商家名通常在金额上方或下方）。
     * 限制总长度避免过长。
     */
    private fun collectNearbyText(root: AccessibilityNodeInfo, amountText: String): String {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(root, allTexts)
        // 找到金额文本的位置，取前后各 3 个非空文本
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
            child.recycle()
        }
    }

    /**
     * 构建节点树摘要字符串（用于日志分析页面结构）。
     * 格式: "L0:viewId=text|L1:viewId=text|..."
     * 记录有文本或有viewId的节点，带层级标记。
     */
    private fun buildNodeTree(node: AccessibilityNodeInfo, maxDepth: Int, depth: Int = 0): String {
        if (depth > maxDepth) return ""
        val sb = StringBuilder()
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val text = node.text?.toString()?.trim()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.take(30) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""

        // 只记录有信息的节点
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
            child.recycle()
        }
        // 限制总长度防止爆日志
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
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }
}
