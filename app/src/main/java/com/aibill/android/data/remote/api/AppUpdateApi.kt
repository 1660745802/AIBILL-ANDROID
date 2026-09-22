package com.aibill.android.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 自托管更新检查 API（PR：通过 billserver 自管更新链接）
 *
 * baseUrl 复用 ServerUrlInterceptor 拦截后的用户配置服务器地址，
 * 路径：`<server>/api/app/update`
 *
 * 优势：
 * - 不依赖 GitHub 可达性（内网部署 / 国内环境）
 * - billserver 可控制灰度（按账号 / versionCode 比例放量）
 * - 失败 fallback 到 GitHub Release（UpdateManager 策略）
 */
interface AppUpdateApi {

    /**
     * 查询是否有新版本
     *
     * @param currentVersionName 当前 App 的 versionName（如 "1.2.0"）
     * @param currentVersionCode 当前 App 的 versionCode（如 3）
     * @param platform 平台标识（固定 "android"）
     */
    @GET("app/update")
    suspend fun checkUpdate(
        @Query("versionName") currentVersionName: String,
        @Query("versionCode") currentVersionCode: Int,
        @Query("platform") platform: String = "android",
    ): AppUpdateDto
}

/**
 * App 更新响应 DTO
 *
 * 设计：hasUpdate=false 时其它字段为 null，App 端据此判定"已是最新"。
 * forceUpdate=true 时忽略用户延迟，强制走下载流程（按需启用）。
 */
@JsonClass(generateAdapter = true)
data class AppUpdateDto(
    @Json(name = "has_update") val hasUpdate: Boolean = false,
    @Json(name = "latest_version") val latestVersion: String? = null,
    @Json(name = "latest_version_code") val latestVersionCode: Int? = null,
    @Json(name = "force_update") val forceUpdate: Boolean = false,
    @Json(name = "changelog") val changelog: String? = null,
    @Json(name = "apk_url") val apkUrl: String? = null,
    @Json(name = "apk_size") val apkSize: Long = 0,
)