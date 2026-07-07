package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.util.NotificationParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 支付页面无障碍识别服务
 *
 * 监听微信/支付宝的支付结果页面，从 View 节点树提取金额和商家名。
 * 覆盖"支付无通知"的场景（如用户关了通知、App内静默支付）。
 *
 * 提取到金额后，走统一入库流程（通过 NotificationBuffer 去重，避免和通知重复）。
 */
@AndroidEntryPoint
class PaymentAccessibilityService : AccessibilityService() {

    @Inject lateinit var notificationBuffer: NotificationBuffer
    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var appLogger: com.aibill.android.util.AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 防抖：同一页面 5s 内不重复处理 */
    private var lastProcessedKey: String? = null
    private var lastProcessedTime: Long = 0L

    companion object {
        private const val DEBOUNCE_MS = 5000L
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        // 微信支付结果页 Activity 类名（可能随版本更新变化）
        private val WECHAT_PAY_ACTIVITIES = setOf(
            "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI",
            "com.tencent.mm.plugin.wallet.pay.ui.WalletPayResultUI",
        )

        // 支付宝支付结果页 Activity 类名
        private val ALIPAY_PAY_ACTIVITIES = setOf(
            "com.alipay.mobile.nebulacore.ui.H5Activity", // 多数支付结果走 H5
            "com.alipay.android.msp.ui.views.MspPayResultActivity",
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
            packageNames = arrayOf(PACKAGE_WECHAT, PACKAGE_ALIPAY)
        }
        appLogger.info("A11Y", "无障碍服务已连接")
        Timber.d("PaymentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // 只处理页面切换事件
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val className = ev.className?.toString() ?: return

        // 判断是否是支付结果页
        val isPaymentResult = when (packageName) {
            PACKAGE_WECHAT -> className in WECHAT_PAY_ACTIVITIES
            PACKAGE_ALIPAY -> className in ALIPAY_PAY_ACTIVITIES
            else -> false
        }
        if (!isPaymentResult) return

        // 防抖
        val key = "$packageName:$className"
        val now = System.currentTimeMillis()
        if (key == lastProcessedKey && (now - lastProcessedTime) < DEBOUNCE_MS) return
        lastProcessedKey = key
        lastProcessedTime = now

        appLogger.info("A11Y", "检测到支付页: pkg=$packageName class=$className")

        // 尝试提取支付信息
        serviceScope.launch {
            val rootNode = rootInActiveWindow ?: return@launch
            val result = extractPaymentInfo(packageName, rootNode)
            rootNode.recycle()

            if (result != null) {
                appLogger.info("A11Y", "✓识别到支付: ¥${"%.2f".format(result.amount/100.0)} 商家=${result.merchant}")
                Timber.d("无障碍识别到支付: ${result.amount}分, ${result.merchant}")
                enqueueToBuffer(packageName, result)
            } else {
                appLogger.debug("A11Y", "支付页未提取到金额: $packageName")
            }
        }
    }

    override fun onInterrupt() {
        appLogger.warn("A11Y", "无障碍服务被中断")
        Timber.w("PaymentAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        appLogger.warn("A11Y", "无障碍服务销毁")
        serviceScope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // 支付信息提取
    // ═══════════════════════════════════════════════════════════════

    data class PayResult(
        val amount: Int, // 分
        val merchant: String?,
    )

    private fun extractPaymentInfo(packageName: String, root: AccessibilityNodeInfo): PayResult? {
        return when (packageName) {
            PACKAGE_WECHAT -> extractWechatPay(root)
            PACKAGE_ALIPAY -> extractAlipayPay(root)
            else -> null
        }
    }

    /**
     * 微信支付结果页提取。
     * 特征：页面包含"支付成功"文本 + "¥XX.XX"金额。
     */
    private fun extractWechatPay(root: AccessibilityNodeInfo): PayResult? {
        // 查找"支付成功"关键词确认是结果页
        val successNodes = root.findNodesByText("支付成功")
        if (successNodes.isNullOrEmpty()) {
            root.findNodesByText("付款成功")?.takeIf { it.isNotEmpty() } ?: return null
        }

        // 提取金额（¥XX.XX 格式）
        val amountText = findAmountText(root) ?: return null
        val amount = notificationParser.extractAmountOnly(amountText) ?: return null

        // 提取商家名（"收款方"附近的文本）
        val merchant = findMerchantText(root)

        return PayResult(amount = amount, merchant = merchant)
    }

    /**
     * 支付宝支付结果页提取。
     * 特征：页面包含"支付成功"/"付款成功" + 金额。
     */
    private fun extractAlipayPay(root: AccessibilityNodeInfo): PayResult? {
        val hasSuccess = !root.findNodesByText("支付成功").isNullOrEmpty() ||
            !root.findNodesByText("付款成功").isNullOrEmpty() ||
            !root.findNodesByText("交易成功").isNullOrEmpty()
        if (!hasSuccess) return null

        val amountText = findAmountText(root) ?: return null
        val amount = notificationParser.extractAmountOnly(amountText) ?: return null
        val merchant = findMerchantText(root)

        return PayResult(amount = amount, merchant = merchant)
    }

    /**
     * 递归查找包含金额格式（¥XX.XX）的节点文本
     */
    private fun findAmountText(root: AccessibilityNodeInfo): String? {
        val amountRegex = Regex("""[¥￥]\s*\d+\.?\d{0,2}""")
        return findTextByPattern(root, amountRegex)
    }

    /**
     * 查找商家名。尝试找"收款方"/"商户"标签附近的文本。
     */
    private fun findMerchantText(root: AccessibilityNodeInfo): String? {
        val labels = listOf("收款方", "商户", "商家", "付款给")
        for (label in labels) {
            val nodes = root.findNodesByText(label) ?: continue
            for (node in nodes) {
                // 尝试取同级下一个兄弟节点的文本
                val parent = node.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrBlank() && text != label && text.length in 2..30) {
                        return text
                    }
                }
            }
        }
        return null
    }

    /**
     * 递归搜索 View 树中匹配正则的文本
     */
    private fun findTextByPattern(node: AccessibilityNodeInfo, pattern: Regex): String? {
        val text = node.text?.toString()
        if (text != null && pattern.containsMatchIn(text)) return text

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByPattern(child, pattern)
            if (result != null) return result
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // 入统一缓冲池（与通知渠道共用去重）
    // ═══════════════════════════════════════════════════════════════

    private suspend fun enqueueToBuffer(packageName: String, result: PayResult) {
        val fullText = buildString {
            append("支付成功 ")
            append("¥${"%.2f".format(result.amount / 100.0)}")
            if (!result.merchant.isNullOrBlank()) append(" ${result.merchant}")
        }

        val item = NotificationBuffer.BufferedItem(
            packageName = packageName,
            title = "支付成功",
            fullText = fullText,
            roughAmount = result.amount,
        )

        // 60s 去重
        if (notificationBuffer.isDuplicateInWindow(item)) {
            appLogger.debug("A11Y", "60s去重: 通知已处理 amount=${result.amount}")
            Timber.d("无障碍去重：已处理 amount=${result.amount}")
            return
        }

        // 加入合并池（通知服务的 5s 延迟会把它合并进去）
        notificationBuffer.addToPendingMerge(item)
    }

    private fun AccessibilityNodeInfo.findNodesByText(text: String): List<AccessibilityNodeInfo>? {
        return findAccessibilityNodeInfosByText(text)
    }
}
