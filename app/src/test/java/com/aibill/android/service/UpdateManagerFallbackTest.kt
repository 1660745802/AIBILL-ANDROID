package com.aibill.android.service

import com.aibill.android.data.remote.api.AppUpdateApi
import com.aibill.android.data.remote.api.AppUpdateDto
import com.aibill.android.data.remote.api.GithubReleaseApi
import com.aibill.android.data.remote.api.GithubReleaseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 覆盖 UpdateManager 双源 fallback 策略：
 * 1. billserver 可达 + hasUpdate=true → 走 billserver，不查 GitHub
 * 2. billserver 可达 + hasUpdate=false → 信任 billserver，不再 fallback GitHub
 * 3. billserver 异常 → fallback GitHub
 * 4. billserver 可达但返回 404/解析失败 → fallback GitHub
 */
class UpdateManagerFallbackTest {

    private val githubReleaseApi: GithubReleaseApi = mockk()
    private val appUpdateApi: AppUpdateApi = mockk(relaxed = true)

    private fun manager(): UpdateManager {
        // Context 没必要 mock（UpdateManager 构造函数 + check/fetchLatest 不调用 Context）
        return UpdateManager(
            context = mockk(relaxed = true),
            githubReleaseApi = githubReleaseApi,
            appUpdateApi = appUpdateApi,
        )
    }

    private fun gitRelease(tag: String = "v1.3.0", body: String = "## New") = GithubReleaseDto(
        tagName = tag,
        name = "Release $tag",
        body = body,
        prerelease = false,
        assets = listOf(
            com.aibill.android.data.remote.api.GithubAssetDto(
                name = "app-release.apk",
                browserDownloadUrl = "https://github.com/.../app-release.apk",
                size = 5000000L,
            ),
        ),
    )

    @Test
    fun `checkUpdate returns billserver when hasUpdate=true`() = runTest {
        coEvery { appUpdateApi.checkUpdate(any(), any()) } returns AppUpdateDto(
            hasUpdate = true,
            latestVersion = "1.3.0",
            latestVersionCode = 4,
            apkUrl = "https://billserver/aibill-1.3.0.apk",
            apkSize = 4500000L,
            changelog = "新功能",
        )

        val info = manager().checkUpdate()

        assertNotNull(info)
        assertEquals("1.3.0", info!!.versionName)
        assertEquals(UpdateManager.Source.BILLSERVER, info.source)
        coVerify(exactly = 0) { githubReleaseApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun `checkUpdate does NOT fallback to github when billserver says up-to-date`() = runTest {
        coEvery { appUpdateApi.checkUpdate(any(), any()) } returns AppUpdateDto(
            hasUpdate = false,
            latestVersion = "1.2.0",
            latestVersionCode = 3,
        )

        val info = manager().checkUpdate()

        // billserver 明确说没新版本，不应 fallback GitHub
        assertNull(info)
        coVerify(exactly = 0) { githubReleaseApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun `checkUpdate falls back to github when billserver throws`() = runTest {
        coEvery { appUpdateApi.checkUpdate(any(), any()) } throws RuntimeException("network error")
        coEvery { githubReleaseApi.getLatestRelease(any(), any()) } returns gitRelease("v1.3.0")

        val info = manager().checkUpdate()

        assertNotNull(info)
        assertEquals("1.3.0", info!!.versionName)
        assertEquals(UpdateManager.Source.GITHUB, info.source)
    }

    @Test
    fun `fetchLatest does NOT fallback when billserver reachable but hasUpdate=false`() = runTest {
        // 手动检查：billserver 可达说没新版本 → 直接返回 null（不查 GitHub）
        coEvery { appUpdateApi.checkUpdate(any(), any()) } returns AppUpdateDto(
            hasUpdate = false,
            latestVersion = "1.2.0",
        )

        val info = manager().fetchLatest()

        assertNull(info)
        coVerify(exactly = 0) { githubReleaseApi.getLatestRelease(any(), any()) }
    }

    @Test
    fun `fetchLatest falls back to github only when billserver unreachable`() = runTest {
        coEvery { appUpdateApi.checkUpdate(any(), any()) } throws java.io.IOException("offline")
        coEvery { githubReleaseApi.getLatestRelease(any(), any()) } returns gitRelease("v1.3.0")

        val info = manager().fetchLatest()

        assertNotNull(info)
        assertEquals(UpdateManager.Source.GITHUB, info?.source)
    }
}