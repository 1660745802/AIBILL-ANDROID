package com.aibill.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aibill.android.R
import com.aibill.android.presentation.MainActivity
import com.aibill.android.service.NotificationActionReceiver

/**
 * 通知弹窗辅助工具
 * 用于展示自动记账确认通知（Heads-up 样式）
 * 支持通知隐私模式（金额显示为 ¥***）
 */
object NotificationHelper {

    private const val CHANNEL_ID = "aibill_auto_record"
    private const val CHANNEL_NAME = "自动记账"
    // PR #40：PRD §4.3 规定 10 秒后自动收起，避免 heads-up 长时间霸屏
    private const val AUTO_DISMISS_DELAY_MS = 10_000L

    /**
     * 创建通知渠道（Android O+）
     * IMPORTANCE_HIGH 确保 Heads-up 弹出
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "检测到支付通知时弹出确认"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setShowBadge(true)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 格式化金额显示
     * @param amountCent 金额（分）
     * @param privacyMode 是否隐私模式
     */
    fun formatAmount(amountCent: Int, privacyMode: Boolean): String {
        return if (privacyMode) {
            "¥***"
        } else {
            "¥${"%.2f".format(amountCent / 100.0)}"
        }
    }

    /**
     * 展示确认通知（Heads-up 弹窗）
     *
     * @param context       上下文
     * @param recordId      NotificationRecordEntity 的 id
     * @param amount        金额，单位：分
     * @param description   消费描述
     * @param source        来源名称（如"微信支付"、"支付宝"）
     * @param privacyMode   是否开启隐私模式
     */
    fun showConfirmNotification(
        context: Context,
        recordId: Long,
        amount: Int,
        description: String?,
        source: String,
        privacyMode: Boolean = false,
        type: String = "expense",
    ) {
        createNotificationChannel(context)

        val notificationId = recordId.toInt()
        val amountDisplay = formatAmount(amount, privacyMode)
        val descDisplay = if (privacyMode) "***" else description
        // PR #38：隐私模式下 source 也隐藏，避免锁屏泄露消费场景
        val sourceDisplay = if (privacyMode) "***" else source

        // 确认 Action
        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM
            putExtra(NotificationActionReceiver.EXTRA_RECORD_ID, recordId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val confirmPending = PendingIntent.getBroadcast(
            context,
            recordId.toInt(),
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 忽略 Action
        val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_IGNORE
            putExtra(NotificationActionReceiver.EXTRA_RECORD_ID, recordId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val ignorePending = PendingIntent.getBroadcast(
            context,
            recordId.toInt() + 10000,
            ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 点击通知正文 → 打开 App 通知中心页
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notification_center")
        }
        val contentPending = PendingIntent.getActivity(
            context,
            recordId.toInt() + 20000,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = buildString {
            append(sourceDisplay)
            append(" · ")
            append(amountDisplay)
            if (!descDisplay.isNullOrBlank()) {
                append("\n")
                append(descDisplay)
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // PR #38：隐私模式标题完全泛化，不区分支出/收入、不暴露来源
            .setContentTitle(
                when {
                    privacyMode -> "检测到一笔新交易"
                    type == "income" -> "💰 检测到一笔收入"
                    else -> "💰 检测到一笔支出"
                }
            )
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_SOUND)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .addAction(0, "✓ 确认记账", confirmPending)
            .addAction(0, "忽略", ignorePending)
            .setVisibility(
                if (privacyMode) NotificationCompat.VISIBILITY_SECRET
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.notify(notificationId, notification)

        // PR #40 + M3：10 秒自动收起（PRD §4.3）。
        // 之前多个 Notification 同时间 post 时只有一个 Handler 引用被持有，
        // GC 可能在 cancel 触发前回收 Runnable。
        // 改为把 Runnable 存到静态 Map 强引用，确保 10s 后真的 cancel。
        scheduleAutoCancel(manager, notificationId, AUTO_DISMISS_DELAY_MS)
    }

    private val pendingCancels = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()
    private val cancelHandler = Handler(Looper.getMainLooper())

    private fun scheduleAutoCancel(
        manager: NotificationManager,
        notificationId: Int,
        delayMs: Long,
    ) {
        // 如果该 notificationId 已有旧 Runnable（同 id 通知被覆盖），先移除旧的
        pendingCancels.remove(notificationId)?.let { cancelHandler.removeCallbacks(it) }

        val runnable = Runnable {
            try {
                manager.cancel(notificationId)
            } finally {
                pendingCancels.remove(notificationId)
            }
        }
        pendingCancels[notificationId] = runnable
        cancelHandler.postDelayed(runnable, delayMs)
    }

    /**
     * 取消指定 notificationId 的自动收起 Runnable。
     * 当用户手动确认/忽略通知时调用，避免 Runnable 残留。
     */
    fun cancelPendingAutoCancel(notificationId: Int) {
        pendingCancels.remove(notificationId)?.let { cancelHandler.removeCallbacks(it) }
    }

    /**
     * 显示 NLS 断连提醒通知
     * 当心跳检测发现通知监听服务被系统杀死时调用
     */
    fun showNlsDisconnectedNotification(context: Context) {
        createNotificationChannel(context)

        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 30000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ 自动记账已断开")
            .setContentText("通知监听服务被系统关闭，点击前往设置重新开启")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(-1, notification)
    }
}
