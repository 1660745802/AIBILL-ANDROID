package com.aibill.android.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * APK 下载完成广播接收器。
 * DownloadManager 下载完成后触发系统安装器。
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
        }
    }

    companion object {
        /** downloadId → 文件名 */
        val pendingDownloads = ConcurrentHashMap<Long, String>()
    }
}
