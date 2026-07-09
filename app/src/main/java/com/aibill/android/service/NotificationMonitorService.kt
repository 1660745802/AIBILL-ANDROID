package com.aibill.android.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationParser
import com.aibill.android.util.NotificationSourceMapping
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知监听服务 v4
 *
 * 架构：排除层 → 内存去重 → DB 1s 去重 → 直接交给 NotificationProcessor
 *
 * v4 改动：
 * - 砍掉 5s 合并池（合并出 800+ 字把 AI 后端打挂，HTTP 400）
 * - 砍掉 NotificationBuffer 长窗口去重（移到 NotificationProcessor 里按金额后置去重）
 * - 每条通知直接调 AI，短文本（≤ 200 字）通过率 100%
 */
@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var notificationRecordDao: NotificationRecordDao
    @Inject lateinit var notificationProcessor: NotificationProcessor
    @Inject lateinit var appLogger: AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val DEDUP_WINDOW_MS = 1000L

        /** 白名单：只处理这些包名的通知 */
        private val WHITELIST_PACKAGES: Set<String> = NotificationSourceMapping.KNOWN_PACKAGES

        /**
         * 支付特征关键词，用于排除层判断微信/支付宝通知是否可能是账务。
         * 银行App/短信App 不需要此判断（全部放行）。
         */
        val PAYMENT_SIGNAL = Regex(
            "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|" +
            "转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|" +
            "余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"
        )

        /** 全局可读的 NLS 连接状态，供权限引导页检测"权限有但未连接" */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        appLogger.info("NLS", "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        appLogger.warn("NLS", "通知监听服务断开")
    }

    override fun onDestroy() {
        isConnected = false
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        serviceScope.launch { handleNotification(notification) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // v3: 不再做通知撤回降级，AI 入库的就是对的
    }

    // ═══════════════════════════════════════════════════════════════
    // L1: 排除层 → L2: 内存去重 → L3: DB 去重 → 交给 Processor
    // ═══════════════════════════════════════════════════════════════

    /** 内存级去重：防止系统短时间内对同一通知多次触发 onNotificationPosted */
    private val recentNotificationKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // 1. 包名白名单
        val packageName = sbn.packageName ?: return
        if (packageName !in WHITELIST_PACKAGES) return

        // 2. 提取通知文本
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        // 合并文本：bigText 是 text 的展开完整版，优先用 bigText（text 可能是截断摘要）
        val fullText = listOf(title, bigText.ifBlank { text }, subText, infoText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (fullText.isBlank()) return

        // 排除系统运行通知和通知分组摘要（无意义）
        if (fullText.contains("正在运行") || fullText.contains("GroupSummary")) return

        // 0. 内存级去重（用内容hash，防系统重复触发+协程竞态）
        val contentKey = "$packageName:${fullText.hashCode()}"
        val now = System.currentTimeMillis()
        val lastSeen = recentNotificationKeys.put(contentKey, now)
        if (lastSeen != null && (now - lastSeen) < 5000L) {
            appLogger.debug("NLS", "内存去重: pkg=$packageName (重复触发)")
            return
        }
        // 每次清理过期 key（>10s），防泄漏
        recentNotificationKeys.entries.removeIf { now - it.value > 10_000L }

        // 3. 排除层：过滤明显不是账务的通知
        if (!isLikelyFinancial(packageName, title, fullText)) {
            appLogger.debug("NLS", "排除: pkg=$packageName title=$title")
            return
        }

        appLogger.info("NLS", "通知放行: pkg=$packageName title=$title text=${text.take(50)} bigText=${bigText.take(50)} fullText=${fullText.take(80)}")

        // 4. 1s 内容去重（防同一条通知被系统多次分发）
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        val duplicate = notificationRecordDao.findDuplicate(packageName, fullText, since)
        if (duplicate != null) return

        // 5. 直接交给 Processor（AI + 后置按金额去重）
        notificationProcessor.process(
            NotificationProcessor.Item(
                packageName = packageName,
                title = title,
                fullText = fullText,
                channel = NotificationProcessor.Channel.NLS,
            )
        )
    }

    /**
     * 排除层：判断通知是否"可能是账务"。
     * 返回 false = 100% 不是账务，直接丢弃。
     * 返回 true = 不确定，交给 AI 判断。
     * 设计原则：极保守，宁可多放不漏。
     */
    private fun isLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
        return when (packageName) {
            "com.tencent.mm" -> {
                // 微信：title 是"微信支付"直接放行
                if (title == "微信支付" || title == "微信支付凭证" || title.contains("零钱")) return true
                // 否则看全文有没有支付特征
                PAYMENT_SIGNAL.containsMatchIn(fullText)
            }
            "com.eg.android.AlipayGphone" -> {
                if (title.contains("支付") || title.contains("账单") ||
                    title.contains("花呗") || title.contains("余额") ||
                    title.contains("到账") || title.contains("收款")) return true
                PAYMENT_SIGNAL.containsMatchIn(fullText)
            }
            else -> {
                // 银行 App：包名含 bank/银行号段 → 全部放行（银行几乎只发账务通知）
                if (packageName.contains("bank") || packageName.startsWith("cmb") ||
                    packageName.contains("icbc") || packageName.contains("ccb") ||
                    packageName.contains("boc") || packageName.contains("abchina")) return true
                // 其他（美团/京东/云闪付等）：用 PAYMENT_SIGNAL 过滤营销
                PAYMENT_SIGNAL.containsMatchIn(fullText)
            }
        }
    }
}