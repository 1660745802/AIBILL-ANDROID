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
                // P0-2 + P1-4：从 Intent 恢复 forceUpdate；用 named args 显式
                // 构造 UpdateInfo，避免后续字段增加时漏传默认值。
                val forceUpdate = intent.getBooleanExtra(EXTRA_FORCE_UPDATE, false)
                // 关闭通知（下载过程中由 DownloadManager 自带进度通知）
                UpdateNotifier.cancel(context)
                updateManager.downloadAndInstall(
                    context,
                    UpdateManager.UpdateInfo(
                        versionName = version,
                        changelog = changelog,
                        apkUrl = url,
                        apkSize = size,
                        // source / forceUpdate 强制升级场景默认为 UNKNOWN / true
                        source = UpdateManager.Source.UNKNOWN,
                        forceUpdate = forceUpdate,
                    ),
                )
            }
            ACTION_DISMISS -> {
                // P0-2：forceUpdate=true 时不响应「稍后」按钮（UpdateNotifier
                // 那边已经隐藏，这里作为冗余防线）
                val forceUpdate = intent.getBooleanExtra(EXTRA_FORCE_UPDATE, false)
                if (forceUpdate) return
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
        // P0-2：是否强制升级（服务端下发 force_update=true）
        const val EXTRA_FORCE_UPDATE = "extra_force_update"
    }
}
