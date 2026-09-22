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
        val entry = pendingDownloads.remove(id) ?: return

        val apkFile = File(context.getExternalFilesDir(null), entry.fileName)
        if (apkFile.exists()) {
            UpdateManager.installApk(context, apkFile)
        } else {
            Timber.w("DownloadCompleteReceiver: APK 文件不存在 path=${apkFile.absolutePath}")
        }
    }

    companion object {
        /** downloadId → (文件名, 注册时间)，用于超时清理避免 map 永久增长 */
        data class PendingEntry(val fileName: String, val registeredAt: Long)

        /** 下载任务 → 注册条目 */
        val pendingDownloads = ConcurrentHashMap<Long, PendingEntry>()

        /** 24 小时过期阈值 */
        private const val ENTRY_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * 清理超过 24h 的过期条目（仅在新的下载入队时触发，避免额外定时任务）。
         * DownloadManager.remove() 不发 COMPLETE 广播，map 中条目会残留，
         * 这里通过 lazy 清理确保内存可控。
         */
        @Synchronized
        fun cleanupExpired() {
            val now = System.currentTimeMillis()
            val expired = pendingDownloads.entries.filter { now - it.value.registeredAt > ENTRY_TTL_MS }
            expired.forEach { pendingDownloads.remove(it.key) }
            if (expired.isNotEmpty()) {
                Timber.d("DownloadCompleteReceiver: 清理过期条目 count=${expired.size}")
            }
        }
    }
}