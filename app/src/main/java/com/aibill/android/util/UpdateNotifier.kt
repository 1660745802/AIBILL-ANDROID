package com.aibill.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aibill.android.R
import com.aibill.android.service.UpdateInstallReceiver
import com.aibill.android.service.UpdateManager

/**
 * APK 更新提示通知（温和：发现新版本 → 用户主动点"立即更新"或"稍后"）。
 *
 * PR：定时检查与手动检查统一走此通知器。
 * - 用户点"立即更新" → UpdateInstallReceiver 触发下载
 * - 用户点"稍后"或左滑通知 → 通知消失，下次定时检查再提醒
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "aibill_update"
    private const val CHANNEL_NAME = "应用更新"
    private const val NOTIFICATION_ID = 70001

    fun showUpdateNotification(context: Context, info: UpdateManager.UpdateInfo) {
        ensureChannel(context)

        // 「立即更新」按钮 → 触发下载广播
        val downloadIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION_DOWNLOAD
            putExtra(UpdateInstallReceiver.EXTRA_VERSION, info.versionName)
            putExtra(UpdateInstallReceiver.EXTRA_URL, info.apkUrl)
            putExtra(UpdateInstallReceiver.EXTRA_SIZE, info.apkSize)
            putExtra(UpdateInstallReceiver.EXTRA_CHANGELOG, info.changelog)
        }
        val downloadPending = PendingIntent.getBroadcast(
            context, 0, downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 「稍后」按钮 → 关闭通知（无副作用），让用户主动去 Settings 触发
        val dismissIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
            action = UpdateInstallReceiver.ACTION_DISMISS
        }
        val dismissPending = PendingIntent.getBroadcast(
            context, 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val changelogText = info.changelog.ifBlank { "点击立即更新升级到 ${info.versionName}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("发现新版本 ${info.versionName}")
            .setContentText(changelogText.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(changelogText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "立即更新", downloadPending)
            .addAction(0, "稍后", dismissPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "有新版本时提示更新（不会自动下载）" }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}