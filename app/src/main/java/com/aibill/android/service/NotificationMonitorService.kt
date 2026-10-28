package com.aibill.android.service

import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureDropHandler
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.CaptureIngress
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 通知监听（NLS）——**平台翻译 adapter**。
 *
 * v4 改动：
 * - 砍掉 5s 合并池（合并出 800+ 字把 AI 后端打挂，HTTP 400）
 * - 砍掉 NotificationBuffer 长窗口去重（移到 NotificationProcessor 里按金额后置去重）
 * - 每条通知直接调 AI，短文本（≤ 200 字）通过率 100%
 *
 * ## 收口后本类只剩两件事（架构候选 2）
 *
 * 1. `StatusBarNotification` → [Capture]（extras 取文本、清洗、拼全文）；
 * 2. 预筛放行 → `NotificationProcessor.process`。
 *
 * 「这条通知值不值得当账」的判据链（含自身通知、日报汇总、促销句式、营销词、
 * 账务判据、去重/记忆窗口、AI 熔断）全在
 * [com.aibill.android.domain.notification.NotificationPrefilter] 后面，
 * 三个渠道同一套——此前它们分别有 7 步 / 2 步 / 一套自己的防抖三套口径。
 */
class NotificationMonitorService : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NlsEntryPoint {
        fun captureIngress(): CaptureIngress
        fun captureFunnelStats(): CaptureFunnelStats
        fun captureDropHandler(): CaptureDropHandler
        fun appLogger(): AppLogger
    }

    private lateinit var ingress: CaptureIngress
    private lateinit var captureFunnelStats: CaptureFunnelStats
    private lateinit var captureDropHandler: CaptureDropHandler
    private lateinit var appLogger: AppLogger

    companion object {
        /** 包名缺失时的占位（服务端 `package` 必填；这种早退本就没有归因信息）。 */
        private const val UNKNOWN_PACKAGE = "unknown"

        /** 全局可读的 NLS 连接状态，供权限引导页检测"权限有但未连接" */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, NlsEntryPoint::class.java)
        ingress = entryPoint.captureIngress()
        captureFunnelStats = entryPoint.captureFunnelStats()
        captureDropHandler = entryPoint.captureDropHandler()
        appLogger = entryPoint.appLogger()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        // 恢复即撤：残留的「已断开」通知断言的是一个已不成立的状态（PRODUCT §五.4）。
        NotificationHelper.cancelNlsDisconnectedNotification(this)
        appLogger.info("NLS", "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        appLogger.warn("NLS", "通知监听服务断开，尝试自动恢复")
        try {
            requestRebind(android.content.ComponentName(this, NotificationMonitorService::class.java))
            appLogger.info("NLS", "已请求 requestRebind")
        } catch (e: Exception) {
            appLogger.error("NLS", "requestRebind 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // C02：launch 与取消/异常处理收进 CaptureIngress；receipt 仍在同步路径计。
        handleNotification(notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // v3: 不再做通知撤回降级，AI 入库的就是对的
    }

    /** 平台翻译：`StatusBarNotification` → [Capture]，判定与放行都交给预筛 module。 */
    private fun handleNotification(sbn: StatusBarNotification) {
        captureFunnelStats.recordReceived(SourceChannel.NLS)
        // 绝不让异常逃出 onNotificationPosted：它跑在 **NLS 的 binder 线程**上，
        // 抛出 = 监听服务崩溃 = 「通知监听不到」，而进程被杀时 onListenerDisconnected
        // 根本不会被调用，只能靠系统重绑（MIUI 上经常不重绑）。
        // catch Throwable：单条畸形通知不该让整个监听服务挂掉。
        try {
            translateAndAdmit(sbn)
        } catch (e: Throwable) {
            appLogger.error(
                "NLS",
                "通知处理异常（已兜住，服务保活）: ${e.javaClass.simpleName}: ${e.message} " +
                    "pkg=${sbn.packageName}",
            )
        }
    }

    private fun translateAndAdmit(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == null) {
            // 捕获层早退：包名为空的通知无法归因，预筛也看不到（不重复计）
            captureDropHandler.onDrop(CaptureDrop.NLS_NO_PACKAGE, UNKNOWN_PACKAGE)
            return
        }

        val extras = sbn.notification?.extras
        if (extras == null) {
            captureDropHandler.onDrop(CaptureDrop.NLS_NO_EXTRAS, packageName)
            return
        }
        val title = sanitizeNotificationText(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        )
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        // 合并文本：bigText 是 text 的展开完整版，优先用 bigText（text 可能是截断摘要）。
        // 群聊类通知（QQ/微信）的 bigText 往往以 title 开头，此时不再重复拼 title，
        // 否则 AI 会收到「209嗨 209嗨 太越林上火型: …」这种双份噪音。
        val body = sanitizeNotificationText(bigText.ifBlank { text })
        val fullText = buildFullText(title, body, subText, infoText)

        // 交给入管道：预筛 → 放行则入账（C02 收口；不再各自 launch/evaluate/process）
        ingress.admit(
            Capture(
                channel = SourceChannel.NLS,
                packageName = packageName,
                title = title,
                fullText = fullText,
                receivedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 合并 title / 正文 / 副标题，title 若已是正文前缀则不重复拼接。
     */
    private fun buildFullText(title: String, body: String, subText: String, infoText: String): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank() && !body.startsWith(title)) parts.add(title)
        if (body.isNotBlank()) parts.add(body)
        if (subText.isNotBlank()) parts.add(sanitizeNotificationText(subText))
        if (infoText.isNotBlank()) parts.add(sanitizeNotificationText(infoText))
        return parts.joinToString(" ").trim()
    }

    /**
     * 清洗通知原文：剔除控制字符 / 私用区字符 / 变体选择符 / 零宽字符。
     *
     * 群名里的 emoji 与代理对经常混进私用区码位（如日志里的 `209<$ÿĀ>`），
     * 原样送 AI 既浪费 token 又可能触发后端 5001。
     * 只做减法（黑名单），不做白名单过滤，避免误删正常标点与 CJK。
     */
    private fun sanitizeNotificationText(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw) {
            val code = ch.code
            val drop = code < 0x20 ||                       // C0 控制字符（\n \t 交给下面的空白归一）
                    code == 0x7F ||                        // DEL
                    code == 0xFFFD ||                      // 替换字符 U+FFFD
                    code in 0xE000..0xF8FF ||               // 私用区
                    code in 0xF0000..0xFFFFD ||             // 私用区（增补平面 A）
                    code in 0x100000..0x10FFFD ||           // 私用区（增补平面 B）
                    code in 0xFE00..0xFE0F ||               // 变体选择符
                    code in 0x200B..0x200F ||               // 零宽字符
                    code == 0x2060 ||                      // 单词连接符
                    Character.getType(ch) == Character.FORMAT.toInt()
            if (drop) continue
            val isSpace = ch.isWhitespace()
            if (isSpace) {
                if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
            } else {
                sb.append(ch)
            }
            lastWasSpace = isSpace
        }
        return sb.toString().trim()
    }
}
