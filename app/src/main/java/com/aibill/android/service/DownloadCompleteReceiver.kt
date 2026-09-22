package com.aibill.android.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * APK 下载完成广播接收器。
 *
 * 安全模型：
 * - AndroidManifest 中 `android:permission="android.permission.ACCESS_DOWNLOAD_MANAGER"`
 *   要求发送方必须持有系统级 signature 权限，只有 DownloadManager 服务能发出。
 * - 此处仍保留 [SYSTEM_DOWNLOAD_PACKAGE] 校验作为冗余防线（防止 manifest 误配置）。
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        val fileName = pendingDownloads.remove(id) ?: return

        val apkFile = File(context.getExternalFilesDir(null), fileName)
        if (apkFile.exists()) {
            UpdateManager.installApk(context, apkFile)
        } else {
            Timber.w("DownloadCompleteReceiver: APK 文件不存在 path=${apkFile.absolutePath}")
        }
    }

    companion object {
        /** downloadId → 文件名 */
        val pendingDownloads = ConcurrentHashMap<Long, String>()
    }
}