package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 处理更新通知的"立即更新"点击 → 触发 APK 下载。
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var updateManager: UpdateManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOWNLOAD) return
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)
        val changelog = intent.getStringExtra(EXTRA_CHANGELOG).orEmpty()

        // 取消更新通知
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(70001)

        updateManager.downloadAndInstall(
            context,
            UpdateManager.UpdateInfo(version, changelog, url, size),
        )
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.aibill.android.ACTION_UPDATE_DOWNLOAD"
        const val EXTRA_VERSION = "extra_version"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SIZE = "extra_size"
        const val EXTRA_CHANGELOG = "extra_changelog"
    }
}
