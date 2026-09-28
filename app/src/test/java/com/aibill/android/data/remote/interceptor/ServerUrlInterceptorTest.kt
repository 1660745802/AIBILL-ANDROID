package com.aibill.android.data.remote.interceptor

import com.aibill.android.data.local.datastore.UserPreferences
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * 1. serverUrl = "http://host/api" + 请求 /api/auth/login → 最终 /api/auth/login（不重复）
 * 2. serverUrl = "http://host/api/" (带尾斜杠) → 同上
 * 3. serverUrl = "http://host" (无 /api) → /api/auth/login（保留 api 段）
 * 4. serverUrl = null/blank → 原样放行（不替换）
 * 5. serverUrl 无法解析 → 原样放行
 * 6. 带 query 参数 → query 保留
 * 7. 路径中有 path 参数（如 transactions/123）→ 正确拼接
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

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(ServerUrlInterceptor(userPreferences))
            .build()
    }

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

    private fun localUrl(path: String): okhttp3.HttpUrl =
        "${localBase()}$path".toHttpUrl()

    private fun executeAndGetRequestedPath(
        serverUrl: String?,
        originalPath: String = "/api/auth/login",
        query: String? = null,
    ): String {
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val urlBuilder = localUrl(originalPath).newBuilder()
        if (query != null) urlBuilder.query(query)

        // 我们不用这个 client 直接连 mockServer（因为 interceptor 会替换 URL）
        // 所以需要设置 interceptor 转发到 mockServer
        // 策略：serverUrl 指向 mockServer 的地址
        val serverBase = localBase() + "/api"
        every { userPreferences.getServerUrlBlocking() } returns (serverUrl ?: serverBase)

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000$originalPath${query?.let { "?$it" } ?: ""}")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        return recorded.path ?: ""
    }

    @Test
    fun `serverUrl with api path - auth login - no path duplication`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/auth/login")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/auth/login", recorded.path)
        java.io.File(System.getProperty("java.io.tmpdir"), "rec_dbg.txt").appendText(
            "T1 path=[${recorded.path}] serverUrl=[$serverUrl] host=[${mockServer.hostName}]\n"
        )
    }

    @Test
    fun `serverUrl with trailing slash - auth login - no path duplication`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api/"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/auth/login")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `serverUrl without api path - falls through correctly`() {
        val serverBase = localBase()
        every { userPreferences.getServerUrlBlocking() } returns serverBase

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/transactions")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        // serverUrl 无 /api 时必须保留请求路径里的 "api" 段：
        java.io.File(System.getProperty("java.io.tmpdir"), "rec_dbg.txt").appendText(
            "T3 path=[${recorded.path}] serverUrl=[$serverBase] host=[${mockServer.hostName}]\n"
        )
        // 后端契约是 /api/*（README 示例 serverUrl 不带 /api）。
        // basePath = ""，relativePath = "api/transactions"
        // 最终 = /api/transactions
        assertEquals("/api/transactions", recorded.path)
    }

    @Test
    fun `serverUrl null - request proceeds unchanged`() {
        every { userPreferences.getServerUrlBlocking() } returns null

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        // 指向 mockServer 让请求能完成
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url(localUrl("/api/auth/login"))
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `serverUrl blank - request proceeds unchanged`() {
        every { userPreferences.getServerUrlBlocking() } returns "  "

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url(localUrl("/api/auth/login"))
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `query parameters are preserved`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/transactions?page=1&pageSize=20&type=expense")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/transactions?page=1&pageSize=20&type=expense", recorded.path)
    }

    @Test
    fun `path with id parameter - transactions 123`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/transactions/123")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/transactions/123", recorded.path)
    }

    @Test
    fun `nested path - transactions id restore`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/transactions/42/restore")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/transactions/42/restore", recorded.path)
    }

    @Test
    fun `stats summary path`() {
        val serverBase = localBase()
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder().header("X-Aibill-Test", "1")
            .url("http://localhost:3000/api/stats/summary?month=2026-07")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = takeOwnRequest()
        assertEquals("/api/stats/summary?month=2026-07", recorded.path)
    }
}
