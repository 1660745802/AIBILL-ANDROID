package com.aibill.android.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aibill.android.BuildConfig
import com.aibill.android.data.remote.api.GithubReleaseApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APK 自动更新管理器（GitHub Release）。
 *
 * 流程：checkUpdate() 查 GitHub 最新 Release → 版本对比 →
 * downloadAndInstall() 用 DownloadManager 下载 → FileProvider 触发系统安装器。
 *
 * 静默处理所有异常，网络失败不影响 App 正常使用。
 */
@Singleton
class UpdateManager @Inject constructor(
    private val api: GithubReleaseApi,
) {
    data class UpdateInfo(
        val versionName: String,
        val changelog: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /**
     * 检查更新。返回 null 表示已是最新或检查失败。
     */
    suspend fun checkUpdate(): UpdateInfo? {
        return try {
            val release = api.getLatestRelease(OWNER, REPO)
            if (release.prerelease) return null
            val tag = release.tagName?.removePrefix("v")?.trim() ?: return null
            // 版本对比：语义化 versionName（如 1.4.2）
            if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return null
            toUpdateInfo(release, tag)
        } catch (e: Exception) {
            Timber.w(e, "UpdateManager: check failed")
            null
        }
    }

    /**
     * 获取最新版本（不做版本对比）。手动"检查更新"用，
     * 允许强制重装/回滚到 GitHub 最新 Release。
     */
    suspend fun fetchLatest(): UpdateInfo? {
        return try {
            val release = api.getLatestRelease(OWNER, REPO)
            val tag = release.tagName?.removePrefix("v")?.trim() ?: return null
            toUpdateInfo(release, tag)
        } catch (e: Exception) {
            Timber.w(e, "UpdateManager: fetchLatest failed")
            null
        }
    }

    private fun toUpdateInfo(
        release: com.aibill.android.data.remote.api.GithubReleaseDto,
        tag: String,
    ): UpdateInfo? {
        val apk = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true } ?: return null
        val url = apk.browserDownloadUrl ?: return null
        return UpdateInfo(
            versionName = tag,
            changelog = release.body?.trim().orEmpty(),
            apkUrl = url,
            apkSize = apk.size,
        )
    }

    /**
     * 下载 APK 并触发安装。用系统 DownloadManager（通知栏显示进度）。
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        try {
            val fileName = "aibill-${info.versionName}.apk"
            // 清理旧的下载文件
            val destDir = context.getExternalFilesDir(null)
            File(destDir, fileName).takeIf { it.exists() }?.delete()

            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("AIBILL 更新 ${info.versionName}")
                .setDescription("正在下载新版本…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, fileName)
                .setMimeType(APK_MIME)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            // 记录 downloadId，下载完成广播里触发安装
            DownloadCompleteReceiver.pendingDownloads[downloadId] = fileName
            Timber.d("UpdateManager: download started id=$downloadId")
        } catch (e: Exception) {
            Timber.e(e, "UpdateManager: download failed")
        }
    }

    companion object {
        private const val OWNER = "1660745802"
        private const val REPO = "AIBILL-ANDROID"
        const val APK_MIME = "application/vnd.android.package-archive"

        /** 触发系统安装器 */
        fun installApk(context: Context, apkFile: File) {
            try {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apkFile,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Timber.e(e, "UpdateManager: install failed")
            }
        }

        /**
         * 语义化版本对比：remote 是否比 current 新。
         * 支持 1.4.2 / 1.4 / 1 格式，逐段数字比较。
         */
        fun isNewerVersion(remote: String, current: String): Boolean {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            val max = maxOf(r.size, c.size)
            for (i in 0 until max) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}
