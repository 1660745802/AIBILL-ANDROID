package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aibill.android.util.NotificationParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 支付页面无障碍识别服务（v2）
 *
 * 不依赖 Activity 类名（微信频繁改名），而是通过遍历页面节点树
 * 查找"支付成功"关键词 + 金额文字来判断当前是否在支付结果页。
 *
 * 监听 TYPE_WINDOW_CONTENT_CHANGED + TYPE_WINDOW_STATE_CHANGED，
 * 只要页面上同时出现"支付成功"和"¥XX.XX"就触发提取。
 */
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var notificationBuffer: NotificationBuffer
    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 防抖：同一金额 10s 内不重复处理 */
    private var lastAmount: Int? = null
    private var lastTime: Long = 0L

    companion object {
        private const val DEBOUNCE_MS = 10_000L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
        private val PAYMENT_APPS = setOf(PACKAGE_WECHAT, PACKAGE_ALIPAY)

        /** 支付成功关键词（页面上出现这些词说明在支付结果页） */
        private val SUCCESS_KEYWORDS = listOf("支付成功", "付款成功", "交易成功", "支付完成")

        /** 金额正则（从节点文字中提取） */
        private val AMOUNT_REGEX = Regex("""[¥￥]\s*(\d+\.?\d{0,2})""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLogger.info("A11Y", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // 只关注微信/支付宝
        if (packageName !in PAYMENT_APPS) return

        // 监听页面切换和内容变化
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ev.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // 获取当前页面节点树
        val rootNode = rootInActiveWindow ?: return

        try {
            // 遍历节点树：找"支付成功"关键词
            if (!hasKeywordInTree(rootNode, SUCCESS_KEYWORDS)) return

            // 页面有"支付成功" → 提取金额
            val amount = findAmountInTree(rootNode)
            if (amount == null || amount <= 0) {
                appLogger.debug("A11Y", "支付页检测到但未提取到金额: $packageName")
                return
            }

            // 防抖：同金额 10s 内不重复
            val now = System.currentTimeMillis()
            if (amount == lastAmount && (now - lastTime) < DEBOUNCE_MS) return
            lastAmount = amount
            lastTime = now

            // 提取商家名
            val merchant = findMerchantInTree(rootNode)

            appLogger.info("A11Y", "✓识别支付: ¥${"%.2f".format(amount / 100.0)} 商家=$merchant pkg=$packageName")

            // 入去重池
            serviceScope.launch {
                enqueueToBuffer(packageName, amount, merchant)
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
    // 节点树遍历
    // ═══════════════════════════════════════════════════════════════

    /**
     * 遍历节点树判断是否包含关键词
     */
    private fun hasKeywordInTree(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = root.text?.toString()
        if (text != null && keywords.any { text.contains(it) }) return true

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (hasKeywordInTree(child, keywords)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * 遍历节点树提取金额（¥XX.XX 格式）
     * 返回金额（分）
     */
    private fun findAmountInTree(root: AccessibilityNodeInfo): Int? {
        val text = root.text?.toString()
        if (text != null) {
            val match = AMOUNT_REGEX.find(text)
            if (match != null) {
                val yuan = match.groupValues[1]
                return notificationParser.extractAmountOnly("¥$yuan")
            }
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findAmountInTree(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * 遍历节点树查找商家名。
     * 策略：找"收款方"/"商户"/"付款给"标签同级或下一个节点的文本。
     * 如果找不到标签，取金额节点附近的非数字文本作为描述。
     */
    private fun findMerchantInTree(root: AccessibilityNodeInfo): String? {
        val labels = listOf("收款方", "商户", "商家", "付款给", "收款人")

        // 方式1：通过标签查找
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val parent = node.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    val childText = child.text?.toString()?.trim()
                    if (!childText.isNullOrBlank() && childText != label &&
                        childText.length in 2..30 && !childText.contains("¥")) {
                        child.recycle()
                        parent.recycle()
                        node.recycle()
                        return childText
                    }
                    child.recycle()
                }
                parent.recycle()
                node.recycle()
            }
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // 入去重池
    // ═══════════════════════════════════════════════════════════════

    private suspend fun enqueueToBuffer(packageName: String, amount: Int, merchant: String?) {
        val fullText = buildString {
            append("支付成功 ")
            append("¥${"%.2f".format(amount / 100.0)}")
            if (!merchant.isNullOrBlank()) append(" $merchant")
        }

        val item = NotificationBuffer.BufferedItem(
            packageName = packageName,
            title = "支付成功",
            fullText = fullText,
            roughAmount = amount,
        )

        // 60s 去重：通知渠道可能已处理
        if (notificationBuffer.isDuplicateInWindow(item)) {
            appLogger.debug("A11Y", "60s去重: 通知已处理 amount=$amount")
            return
        }

        // 加入合并池（通知服务的 5s 延迟会把它合并进去）
        notificationBuffer.addToPendingMerge(item)
    }
}
