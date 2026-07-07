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

    /** 防抖：同内容 10s 内不重复处理 */
    private var lastTextHash: Int? = null
    private var lastTime: Long = 0L

    companion object {
        private const val DEBOUNCE_MS = 10_000L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
        private val PAYMENT_APPS = setOf(PACKAGE_WECHAT, PACKAGE_ALIPAY)

        /** 支付成功关键词（必须精确，避免聊天列表里的"到账"文字误触发） */
        private val SUCCESS_KEYWORDS = listOf("支付成功", "付款成功", "交易成功", "支付完成")

        /** 排除词：如果页面同时包含这些词说明不是支付结果页（是聊天列表/首页等） */
        private val EXCLUDE_KEYWORDS = listOf("朋友圈", "视频号", "扫一扫", "搜索小程序", "通讯录", "发现")
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

            // 排除：如果页面同时有"朋友圈/通讯录"等，说明是微信首页/聊天列表，不是支付结果页
            if (hasKeywordInTree(rootNode, EXCLUDE_KEYWORDS)) return

            // 页面有"支付成功" → 收集所有文字拼起来交给 AI
            val allText = collectAllText(rootNode)
            if (allText.isBlank()) return

            // 防抖：同内容 10s 内不重复
            val now = System.currentTimeMillis()
            val textHash = allText.hashCode()
            if (textHash == lastTextHash && (now - lastTime) < DEBOUNCE_MS) return
            lastTextHash = textHash
            lastTime = now

            appLogger.info("A11Y", "✓支付页全文: ${allText.take(100)} pkg=$packageName")

            // 入去重池，后续由通知服务的 5s 合并逻辑统一调 AI
            serviceScope.launch {
                enqueueToBuffer(packageName, allText)
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
     * 收集节点树中所有可见文字，拼成一段发给 AI
     */
    private fun collectAllText(root: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        collectTextsRecursive(root, texts)
        return texts.distinct().joinToString(" ")
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length in 1..100) {
            texts.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextsRecursive(child, texts)
            child.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 入去重池
    // ═══════════════════════════════════════════════════════════════

    private suspend fun enqueueToBuffer(packageName: String, fullText: String) {
        val roughAmount = notificationParser.extractAmountOnly(fullText)

        val item = NotificationBuffer.BufferedItem(
            packageName = packageName,
            title = "支付成功",
            fullText = fullText,
            roughAmount = roughAmount,
        )

        // 60s 去重：通知渠道可能已处理
        if (notificationBuffer.isDuplicateInWindow(item)) {
            appLogger.debug("A11Y", "60s去重: 通知已处理 amount=$roughAmount")
            return
        }

        // 加入合并池
        notificationBuffer.addToPendingMerge(item)
    }
}
