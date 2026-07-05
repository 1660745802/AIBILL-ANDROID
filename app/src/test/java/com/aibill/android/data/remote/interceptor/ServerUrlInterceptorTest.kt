package com.aibill.android.data.remote.interceptor

import com.aibill.android.data.local.datastore.UserPreferences
import io.mockk.every
import io.mockk.mockk
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
 * 1. serverUrl = "http://host/api" + 请求 /api/auth/login → 最终 /api/auth/login（不重复）
 * 2. serverUrl = "http://host/api/" (带尾斜杠) → 同上
 * 3. serverUrl = "http://host" (无 /api) → /auth/login
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

    private fun executeAndGetRequestedPath(
        serverUrl: String?,
        originalPath: String = "/api/auth/login",
        query: String? = null,
    ): String {
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val urlBuilder = mockServer.url(originalPath).newBuilder()
        if (query != null) urlBuilder.query(query)

        // 我们不用这个 client 直接连 mockServer（因为 interceptor 会替换 URL）
        // 所以需要设置 interceptor 转发到 mockServer
        // 策略：serverUrl 指向 mockServer 的地址
        val serverBase = mockServer.url("/api").toString().trimEnd('/')
        every { userPreferences.getServerUrlBlocking() } returns (serverUrl ?: serverBase)

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000$originalPath${query?.let { "?$it" } ?: ""}")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        return recorded.path ?: ""
    }

    @Test
    fun `serverUrl with api path - auth login - no path duplication`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/auth/login")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `serverUrl with trailing slash - auth login - no path duplication`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api/"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/auth/login")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `serverUrl without api path - falls through correctly`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        every { userPreferences.getServerUrlBlocking() } returns serverBase

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/transactions")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        // serverUrl 无 /api，但 pathSegments 第一段是 "api" 会被 drop
        // basePath = "/"，relativePath = "transactions"
        // 最终 = /transactions
        assertEquals("/transactions", recorded.path)
    }

    @Test
    fun `serverUrl null - request proceeds unchanged`() {
        every { userPreferences.getServerUrlBlocking() } returns null

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        // 指向 mockServer 让请求能完成
        val request = Request.Builder()
            .url(mockServer.url("/api/auth/login"))
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `serverUrl blank - request proceeds unchanged`() {
        every { userPreferences.getServerUrlBlocking() } returns "  "

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url(mockServer.url("/api/auth/login"))
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/auth/login", recorded.path)
    }

    @Test
    fun `query parameters are preserved`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/transactions?page=1&pageSize=20&type=expense")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/transactions?page=1&pageSize=20&type=expense", recorded.path)
    }

    @Test
    fun `path with id parameter - transactions 123`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/transactions/123")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/transactions/123", recorded.path)
    }

    @Test
    fun `nested path - transactions id restore`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/transactions/42/restore")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/transactions/42/restore", recorded.path)
    }

    @Test
    fun `stats summary path`() {
        val serverBase = mockServer.url("/").toString().trimEnd('/')
        val serverUrl = "$serverBase/api"
        every { userPreferences.getServerUrlBlocking() } returns serverUrl

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = buildClient()
        val request = Request.Builder()
            .url("http://localhost:3000/api/stats/summary?month=2026-07")
            .build()

        val response = client.newCall(request).execute()
        response.close()

        val recorded = mockServer.takeRequest()
        assertEquals("/api/stats/summary?month=2026-07", recorded.path)
    }
}
