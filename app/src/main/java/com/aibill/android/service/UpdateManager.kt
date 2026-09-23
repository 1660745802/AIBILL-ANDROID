package com.aibill.android.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aibill.android.BuildConfig
import com.aibill.android.data.remote.api.AppUpdateApi
import com.aibill.android.data.remote.api.AppUpdateDto
import com.aibill.android.data.remote.api.GithubReleaseApi
import com.aibill.android.data.remote.api.GithubReleaseDto
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APK 自动更新管理器（PR：billserver 自管链接 + GitHub fallback）。
 *
 * 优先级策略（双源）：
 * 1. 优先调 billserver 的 `GET /api/app/update`（自托管，内网可达）
 * 2. billserver 失败（无 serverUrl / 网络错误 / 4xx 5xx）→ fallback GitHub Release
 * 3. 全部失败 → 返回 null（App 显示"已是最新"或"获取失败"）
 *
 * 兼容性：旧版 App（无 AppUpdateApi）走纯 GitHub 路径；新版 App 同时支持两路。
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val githubReleaseApi: GithubReleaseApi,
    private val appUpdateApi: AppUpdateApi,
) {
    data class UpdateInfo(
        val versionName: String,
        val changelog: String,
        val apkUrl: String,
        val apkSize: Long,
        val source: Source = Source.UNKNOWN,
        val forceUpdate: Boolean = false,
    )

    /** 更新源标识（诊断 / 未来统计用） */
    enum class Source { BILLSERVER, GITHUB, UNKNOWN }

    /**
     * billserver 检查结果三态：
     * - Success(info)：billserver 可达且 hasUpdate=true
     * - NoUpdate：billserver 可达且 hasUpdate=false（不 fallback GitHub）
     * - Failure(throwable)：billserver 不可达（fallback GitHub）
     *
     * 用 sealed class 而非 nullable，区分"无版本"和"查询失败"。
     */
    private sealed class BillserverResult {
        data class Success(val info: UpdateInfo) : BillserverResult()
        data object NoUpdate : BillserverResult()
        data class Failure(val throwable: Throwable) : BillserverResult()
    }

    /**
     * 检查更新（带双源 fallback）。
     *
     * 优先级策略：
     * 1. billserver 可达（无论 hasUpdate）→ 信任 billserver 判定（公司可控源）
     *    - hasUpdate=true → 返回该版本
     *    - hasUpdate=false → 返回 null（"已是最新"，不 fallback GitHub）
     * 2. billserver 不可达（异常）→ fallback GitHub
     */
    suspend fun checkUpdate(): UpdateInfo? {
        // 第一优先级：billserver 自管更新
        return when (val result = queryBillserver()) {
            is BillserverResult.Success -> result.info.copy(source = Source.BILLSERVER)
            is BillserverResult.NoUpdate -> {
                Timber.d("UpdateManager: billserver says up-to-date")
                null  // 已是最新，不 fallback GitHub
            }
            is BillserverResult.Failure -> {
                Timber.w(result.throwable, "UpdateManager: billserver unavailable, fallback to GitHub")
                githubReleaseUpdate()?.copy(source = Source.GITHUB)
            }
        }
    }

    /**
     * 获取最新版本（不做版本对比），手动"检查更新"用。
     *
     * 优先级：
     * 1. billserver 自托管更新（可信源，公司可控）
     *    - hasUpdate=true → 返回该版本
     *    - hasUpdate=false → 返回"已是最新"（不 fallback GitHub，避免误判）
     *    - 网络异常 → fallback GitHub
     * 2. GitHub Release（兜底）
     *
     * 注：nullable 版本，无法区分"已是最新"和"获取失败"——
     * UI 层请用 [fetchLatestResult] 获取三态。
     */
    suspend fun fetchLatest(): UpdateInfo? {
        return when (val r = fetchLatestResult()) {
            is FetchLatestResult.Success -> r.info
            else -> null
        }
    }

    /**
     * 手动"检查更新"的三态结果（与 [fetchLatest] 同逻辑，保留语义区分）：
     * - Success(info)：拿到最新版本（可能比本地新，也可能旧/相同，UI 再做版本对比）
     * - NoUpdate：billserver 可达且报告已是最新（服务端权威，不 fallback GitHub）
     * - Failure(message)：所有源都拿不到（billserver 不可达 且 GitHub 也失败）
     */
    sealed class FetchLatestResult {
        data class Success(val info: UpdateInfo) : FetchLatestResult()
        data object NoUpdate : FetchLatestResult()
        data class Failure(val message: String) : FetchLatestResult()
    }

    suspend fun fetchLatestResult(): FetchLatestResult {
        return when (val result = queryBillserver()) {
            is BillserverResult.Success -> FetchLatestResult.Success(
                result.info.copy(source = Source.BILLSERVER),
            )
            is BillserverResult.NoUpdate -> {
                Timber.d("UpdateManager: billserver reachable, no update available")
                FetchLatestResult.NoUpdate
            }
            is BillserverResult.Failure -> {
                Timber.w(result.throwable, "UpdateManager: billserver check failed, fallback to GitHub")
                val fromGithub = githubReleaseUpdate(forceLatest = true)
                if (fromGithub != null) {
                    FetchLatestResult.Success(fromGithub.copy(source = Source.GITHUB))
                } else {
                    FetchLatestResult.Failure("获取失败：billserver 不可达且 GitHub 拉取失败")
                }
            }
        }
    }

    /**
     * 调 billserver 自托管更新 API，返回三态 [BillserverResult]。
     */
    private suspend fun queryBillserver(): BillserverResult {
        return runCatching {
            appUpdateApi.checkUpdate(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
            )
        }.fold(
            onSuccess = { dto ->
                if (dto.hasUpdate && dto.apkUrl != null) {
                    val info = dto.toUpdateInfo(source = Source.BILLSERVER)
                    if (info != null) BillserverResult.Success(info)
                    else BillserverResult.NoUpdate  // DTO 字段缺失视为"无更新"
                } else {
                    BillserverResult.NoUpdate
                }
            },
            onFailure = { e ->
                BillserverResult.Failure(e)
            },
        )
    }

/**
     * GitHub Release fallback。forceLatest=true 时不做版本对比（手动检查用）。
     */
    private suspend fun githubReleaseUpdate(forceLatest: Boolean = false): UpdateInfo? {
        return try {
            val release = githubReleaseApi.getLatestRelease(OWNER, REPO)
            if (release.prerelease) return null
            val tag = release.tagName?.removePrefix("v")?.trim() ?: return null
            if (!forceLatest && !isNewerVersion(tag, BuildConfig.VERSION_NAME)) return null
            toUpdateInfo(release, tag)
        } catch (e: Exception) {
            Timber.w(e, "UpdateManager: github fallback failed")
            null
        }
    }

    /**
     * DTO → UpdateInfo 映射
     */
    private fun AppUpdateDto.toUpdateInfo(source: Source): UpdateInfo? {
        val url = apkUrl ?: return null
        val version = latestVersion ?: return null
        return UpdateInfo(
            versionName = version,
            changelog = changelog?.trim().orEmpty(),
            apkUrl = url,
            apkSize = apkSize,
            source = source,
            forceUpdate = forceUpdate,
        )
    }

    private fun toUpdateInfo(
        release: GithubReleaseDto,
        tag: String,
    ): UpdateInfo? {
        val apk = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true } ?: return null
        val url = apk.browserDownloadUrl ?: return null
        return UpdateInfo(
            versionName = tag,
            changelog = release.body?.trim().orEmpty(),
            apkUrl = url,
            apkSize = apk.size,
            source = Source.GITHUB,
            forceUpdate = false,
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
            DownloadCompleteReceiver.cleanupExpired() // 入队前 lazy 清理过期条目
            DownloadCompleteReceiver.pendingDownloads[downloadId] =
                DownloadCompleteReceiver.Companion.PendingEntry(
                    fileName = fileName,
                    registeredAt = System.currentTimeMillis(),
                )
            Timber.d("UpdateManager: download started id=$downloadId version=${info.versionName} source=${info.source}")
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
         *
         * P2-3：非数字段（如 "1.4.0-beta" 里的 "-beta"）静默截断为 0，不参与比较。
         * 这样 "1.4.0-beta" 与 "1.4.0" 视为相等，不误判为新版。
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
