package com.aibill.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aibill.android.R
import com.aibill.android.service.UpdateManager
import com.aibill.android.service.UpdateInstallReceiver

/**
 * APK 更新提示通知（温和：点击下载，可忽略）。
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "aibill_update"
    private const val CHANNEL_NAME = "应用更新"
    private const val NOTIFICATION_ID = 70001

    fun showUpdateNotification(context: Context, info: UpdateManager.UpdateInfo) {
        ensureChannel(context)

        // 点击"下载" → 触发下载广播
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

        val changelogText = info.changelog.ifBlank { "点击下载并安装新版本" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("发现新版本 ${info.versionName}")
            .setContentText(changelogText.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(changelogText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "立即更新", downloadPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "有新版本时提示更新" }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
