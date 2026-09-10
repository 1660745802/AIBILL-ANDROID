package com.aibill.android.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * GitHub Release API，用于 APK 自动更新检查。
 * baseUrl: https://api.github.com/
 */
interface GithubReleaseApi {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): GithubReleaseDto
}

@JsonClass(generateAdapter = true)
data class GithubReleaseDto(
    @Json(name = "tag_name") val tagName: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "body") val body: String?,
    @Json(name = "prerelease") val prerelease: Boolean = false,
    @Json(name = "assets") val assets: List<GithubAssetDto>?,
)

@JsonClass(generateAdapter = true)
data class GithubAssetDto(
    @Json(name = "name") val name: String?,
    @Json(name = "browser_download_url") val browserDownloadUrl: String?,
    @Json(name = "size") val size: Long = 0,
)
