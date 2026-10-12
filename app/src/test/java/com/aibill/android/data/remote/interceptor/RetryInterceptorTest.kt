package com.aibill.android.data.remote.interceptor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * `RetryInterceptor` 的行为契约（ARCHITECTURE §五）：
 * - 仅重试幂等请求（GET/HEAD/OPTIONS 或带 `X-Idempotent: true`）；
 * - 仅重试 IOException，且**最多重试 2 次**（首次 + 2 次重试 = 最多 3 次 proceed）；
 * - HTTP 错误响应不重试。
 *
 * 回归护栏：把循环改回 `repeat(MAX_RETRY_COUNT)`（只有 1 次重试）本类立刻红。
 */
class RetryInterceptorTest {

    private val interceptor = RetryInterceptor()
    private val getRequest = Request.Builder()
        .url("https://api.example.com/transactions")
        .build()

    private fun okResponse(): Response = Response.Builder()
        .request(getRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    private fun mockChain(request: Request): Interceptor.Chain = mockk {
        every { this@mockk.request() } returns request
    }

    @Test
    fun `IOException on idempotent GET retries twice then rethrows`() {
        val chain = mockChain(getRequest)
        every { chain.proceed(getRequest) } throws IOException("boom")

        val thrown = assertThrows(IOException::class.java) { interceptor.intercept(chain) }

        assertEquals("boom", thrown.message)
        // 首次 + 2 次重试
        verify(exactly = 3) { chain.proceed(getRequest) }
    }

    @Test
    fun `transient IOException then success - stops retrying as soon as it succeeds`() {
        val chain = mockChain(getRequest)
        val ok = okResponse()
        var calls = 0
        every { chain.proceed(getRequest) } answers {
            calls++
            if (calls == 1) throw IOException("transient") else ok
        }

        val result = interceptor.intercept(chain)

        assertSame(ok, result)
        verify(exactly = 2) { chain.proceed(getRequest) }
    }

    @Test
    fun `HTTP error response is returned without retry`() {
        val chain = mockChain(getRequest)
        val error = Response.Builder()
            .request(getRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("server error")
            .build()
        every { chain.proceed(getRequest) } returns error

        val result = interceptor.intercept(chain)

        assertEquals(500, result.code)
        verify(exactly = 1) { chain.proceed(getRequest) }
    }

    @Test
    fun `non-idempotent POST without X-Idempotent is never retried`() {
        val post = Request.Builder()
            .url("https://api.example.com/transactions")
            .post("".toRequestBody())
            .build()
        val chain = mockChain(post)
        every { chain.proceed(post) } throws IOException("boom")

        assertThrows(IOException::class.java) { interceptor.intercept(chain) }

        verify(exactly = 1) { chain.proceed(post) }
    }

    @Test
    fun `POST with X-Idempotent header is retried`() {
        val post = Request.Builder()
            .url("https://api.example.com/transactions")
            .header("X-Idempotent", "true")
            .post("".toRequestBody())
            .build()
        val chain = mockChain(post)
        every { chain.proceed(post) } throws IOException("boom")

        assertThrows(IOException::class.java) { interceptor.intercept(chain) }

        verify(exactly = 3) { chain.proceed(post) }
    }
}
