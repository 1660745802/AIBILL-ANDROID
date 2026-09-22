package com.aibill.android.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aibill.android.util.UpdateNotifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 处理更新通知按钮点击：
 * - ACTION_DOWNLOAD：「立即更新」→ 触发 APK 下载
 * - ACTION_DISMISS：「稍后」→ 关闭通知（不下载，下个定时周期再提醒）
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var updateManager: UpdateManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DOWNLOAD -> {
                val version = intent.getStringExtra(EXTRA_VERSION) ?: return
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                val size = intent.getLongExtra(EXTRA_SIZE, 0L)
                val changelog = intent.getStringExtra(EXTRA_CHANGELOG).orEmpty()
                // 关闭通知（下载过程中由 DownloadManager 自带进度通知）
                UpdateNotifier.cancel(context)
                updateManager.downloadAndInstall(
                    context,
                    UpdateManager.UpdateInfo(version, changelog, url, size),
                )
            }
            ACTION_DISMISS -> {
                // 用户主动忽略，关闭通知
                UpdateNotifier.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.aibill.android.ACTION_UPDATE_DOWNLOAD"
        const val ACTION_DISMISS = "com.aibill.android.ACTION_UPDATE_DISMISS"
        const val EXTRA_VERSION = "extra_version"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SIZE = "extra_size"
        const val EXTRA_CHANGELOG = "extra_changelog"
    }
}
