package com.aibill.android.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        fun notificationProcessor(): NotificationProcessor
        fun prefilter(): NotificationPrefilter
        fun appLogger(): AppLogger
        @com.aibill.android.di.ApplicationScope
        fun applicationScope(): kotlinx.coroutines.CoroutineScope
    }

    private lateinit var notificationProcessor: NotificationProcessor
    private lateinit var prefilter: NotificationPrefilter
    private lateinit var appLogger: AppLogger

    // PR 修复：改用进程级 @ApplicationScope，
    // 避免 NLS 每次 onCreate 时泄漏一个 SupervisorJob+IO。
    private lateinit var serviceScope: kotlinx.coroutines.CoroutineScope

    companion object {
        /** 全局可读的 NLS 连接状态，供权限引导页检测"权限有但未连接" */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, NlsEntryPoint::class.java)
        notificationProcessor = entryPoint.notificationProcessor()
        prefilter = entryPoint.prefilter()
        appLogger = entryPoint.appLogger()
        serviceScope = entryPoint.applicationScope()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
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
        // ★ 切勿在这里 cancel serviceScope！
        // serviceScope 是进程级 @ApplicationScope（Hilt @Singleton，全 App 共享：
        // SMS/A11Y/外部 Intent/小组件等所有协程都跑在它上面）。
        // NLS 被系统 requestRebind 销毁重建后，cancel 会让整个 App 的协程
        // 静默失效（launch 不执行也不抛异常）：通知零日志、AI 零调用，
        // 直到进程被杀才恢复。（2026-09 回归根因，与 PaymentAccessibilityService
        // 的处理保持一致：进程级 Scope 由 CoroutineScopeModule 持有，不在 Service 里 cancel。）
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        serviceScope.launch { handleNotification(notification) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // v3: 不再做通知撤回降级，AI 入库的就是对的
    }

    /** 平台翻译：`StatusBarNotification` → [Capture]，判定与放行都交给预筛 module。 */
    private suspend fun handleNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        val extras = sbn.notification?.extras ?: return
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
        val body = sanitizeNotificationText(bigText.ifBlank { text})
        val fullText = buildFullText(title, body, subText, infoText)

        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.NLS,
                packageName = packageName,
                title = title,
                fullText = fullText,
                receivedAt = System.currentTimeMillis(),
            )
        )

        if (verdict !is Verdict.Pass) return

        // 交给协调者（解析 → 评分窗口 + 跨渠道金额去重 → 入库/待审）
        notificationProcessor.process(verdict.capture)
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
