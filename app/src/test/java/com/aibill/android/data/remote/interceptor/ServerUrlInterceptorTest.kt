package com.aibill.android.data.remote.interceptor

import com.aibill.android.data.local.datastore.UserPreferences
import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 覆盖 ServerUrlInterceptor URL 拼接逻辑。
 *
 * 关键不变量（锁住 commit 0936120 修复）：
 * 1. serverUrl 含 `api` 段（带或不带尾斜杠）+ 请求 `/api/auth/login` →
 *    最终 `/api/auth/login`（**不重复**）
 * 2. serverUrl 不含 `api` 段 → 保留请求路径里的 `api` 段（后端契约是 `api` 前缀）
 * 3. serverUrl null/blank → 原样放行（不替换 host 与 path）
 * 4. query 参数保留
 * 5. 多段路径按原样追加
 */
class ServerUrlInterceptorTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var userPreferences: UserPreferences

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        userPreferences = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(ServerUrlInterceptor(userPreferences)).build()

    /**
     * 本机网络环境会向新开的监听端口发杂散探测请求（如 GET /），
     * FIFO takeRequest 会把探测请求当成测试请求导致断言失败。
     * 用标记头过滤，只取本测试发出的请求。
     */
    private fun takeOwnRequest(): okhttp3.mockwebserver.RecordedRequest {
        while (true) {
            val req = mockServer.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
                ?: error("未收到测试请求")
            if (req.getHeader("X-Aibill-Test") == "1") return req
        }
    }

    /** MockWebServer 默认反解域名在部分内网环境异常，统一走 localhost */
    private fun localBase(): String = "http://localhost:${mockServer.port}"

    private fun localUrl(path: String): okhttp3.HttpUrl = "${localBase()}$path".toHttpUrl()

    /**
     * 发一条带标记头的请求，返回服务端实际收到的 path。
     *
     * `originalUrl` 是 Retrofit 的占位地址：serverUrl 非空时只借它的 path，
     * host 由 interceptor 换成 serverUrl；serverUrl 为空时原样放行，
     * 因此调用方必须把它指向 mockServer 才收得到请求。
     */
    private fun recordPath(serverUrl: String?, originalUrl: String): String {
        every { userPreferences.getServerUrlBlocking() } returns serverUrl
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder()
            .header("X-Aibill-Test", "1")
            .url(originalUrl)
            .build()
        buildClient().newCall(request).execute().close()
        return takeOwnRequest().path ?: ""
    }

    @Test
    fun `serverUrl with api path, with or without trailing slash - no path duplication`() {
        val base = localBase()
        listOf("$base/api", "$base/api/").forEach { serverUrl ->
            val path = recordPath(serverUrl, "http://localhost:3000/api/auth/login")
            assertEquals("/api/auth/login", path, "serverUrl=$serverUrl")
        }
    }

    @Test
    fun `serverUrl without api path - keeps the api segment from the request`() {
        val path = recordPath(localBase(), "http://localhost:3000/api/transactions")
        assertEquals("/api/transactions", path)
    }

    @Test
    fun `serverUrl null or blank - request proceeds unchanged`() {
        listOf(null, "  ").forEach { serverUrl ->
            val path = recordPath(serverUrl, localUrl("/api/auth/login").toString())
            assertEquals("/api/auth/login", path, "serverUrl=[$serverUrl]")
        }
    }

    @Test
    fun `query parameters are preserved`() {
        val path = recordPath(
            "${localBase()}/api",
            "http://localhost:3000/api/transactions?page=1&pageSize=20&type=expense",
        )
        assertEquals("/api/transactions?page=1&pageSize=20&type=expense", path)
    }

    @Test
    fun `multi-segment paths are appended verbatim`() {
        val serverUrl = "${localBase()}/api"
        listOf(
            "/api/transactions/123",
            "/api/transactions/42/restore",
            "/api/stats/summary?month=2026-07",
        ).forEach { originalPath ->
            val path = recordPath(serverUrl, "http://localhost:3000$originalPath")
            assertEquals(originalPath, path)
        }
    }
}
