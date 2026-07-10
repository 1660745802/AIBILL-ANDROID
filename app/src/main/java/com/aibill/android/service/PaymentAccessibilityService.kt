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
        private val PAYMENT_APPS = setOf(PACKAGE_WECHAT, PACKAGE_ALIPAY)

        /** 支付成功关键词（必须精确匹配这些完整词组，不是子串） */
        private val SUCCESS_KEYWORDS = listOf("支付成功", "付款成功", "交易成功", "支付完成")

        /** 首页/聊天列表特征词——有这些说明不是支付结果页 */
        private val EXCLUDE_KEYWORDS = listOf(
            "朋友圈", "通讯录", "发现", "搜索小程序", "扫一扫",
            "视频号", "看一看", "摇一摇", "附近", "小程序面板",
        )

        /** 金额正则 */
        private val AMOUNT_REGEX = Regex("""[¥￥]\s*(\d+\.?\d{0,2})""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLogger.info("A11Y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        if (packageName !in PAYMENT_APPS) return

        // 监听页面切换和内容变化（支付完成可能不切Activity只刷内容）
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ev.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val rootNode = rootInActiveWindow ?: return

        try {
            // 条件1：有"支付成功"关键词
            if (!hasAnyKeyword(rootNode, SUCCESS_KEYWORDS)) return

            // 条件2：不能有首页/聊天列表特征（排除误触发）
            if (hasAnyKeyword(rootNode, EXCLUDE_KEYWORDS)) return

            // 条件3：有金额文字
            val amountText = findAmount(rootNode) ?: return

            // 三重条件全部满足 → 构建简短摘要
            val merchant = findMerchant(rootNode)
            val summary = buildString {
                append("支付成功 $amountText")
                if (merchant != null) append(" $merchant")
            }

            // 防抖
            val hash = summary.hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash && (now - lastTime) < DEBOUNCE_MS) return
            lastHash = hash
            lastTime = now

            // cooldown：同金额 5 分钟内只触发一次
            val lastAmountTime = recentAmounts[amountText]
            if (lastAmountTime != null && (now - lastAmountTime) < COOLDOWN_MS) return
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
        val labels = listOf("收款方", "商户", "商家", "付款给")
        for (label in labels) {
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
