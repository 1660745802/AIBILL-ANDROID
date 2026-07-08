package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aibill.android.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 支付页面无障碍识别服务（v4）
 *
 * 不依赖 Activity 类名（微信频繁改名），而是通过遍历页面节点树
 * 查找"支付成功"关键词 + 金额文字来判断当前是否在支付结果页。
 *
 * v4 改动：
 * - 砍掉 enqueueToBuffer / NotificationBuffer，直接交给 NotificationProcessor
 * - 10s 内容防抖保留（同页面多次 onAccessibilityEvent 触发）
 * - 后置按金额去重在 NotificationProcessor 内统一做
 */
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var notificationProcessor: NotificationProcessor
    @Inject lateinit var appLogger: AppLogger

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

            // 防抖：同内容 10s 内不重复。归一化后再 hash，避免「¥ vs ￥」「全角空格」导致 hash 不一致
            val now = System.currentTimeMillis()
            val normalized = normalizeText(allText)
            val textHash = normalized.hashCode()
            if (textHash == lastTextHash && (now - lastTime) < DEBOUNCE_MS) return
            lastTextHash = textHash
            lastTime = now

            appLogger.info("A11Y", "✓支付页全文: ${normalized.take(100)} pkg=$packageName")

            // 直接交给 NotificationProcessor（AI + 后置按金额去重）
            serviceScope.launch {
                notificationProcessor.process(
                    NotificationProcessor.Item(
                        packageName = packageName,
                        title = "支付成功",
                        fullText = normalized,
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

    /**
     * 文本归一化：处理全角/半角、空格等字符差异，
     * 让两次抓取的同一支付页产生相同的 hash，10s 防抖才能生效。
     */
    private fun normalizeText(text: String): String =
        text.replace('￥', '¥')          // 全角人民币符号 → 半角
            .replace('　', ' ')           // 全角空格 → 半角空格
            .replace(Regex("\\s+"), " ")  // 多个连续空白 → 单空格
            .trim()

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
}