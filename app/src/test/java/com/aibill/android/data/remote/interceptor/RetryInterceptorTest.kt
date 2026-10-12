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
 * 回归护栏：
 * - 把循环改回 `repeat(MAX_RETRY_COUNT)`（只有 1 次重试）→ 矩阵用例红；
 * - 从 `IDEMPOTENT_METHODS` 删掉 HEAD/OPTIONS，或把 `X-Idempotent` 判定放宽成
 *   「存在即重试」→ 矩阵用例红。
 */
class RetryInterceptorTest {

    private val interceptor = RetryInterceptor()

    private fun request(method: String, idempotent: String? = null): Request {
        val builder = Request.Builder().url("https://api.example.com/transactions")
        if (idempotent != null) builder.header("X-Idempotent", idempotent)
        return builder.method(method, if (method == "POST") "".toRequestBody() else null).build()
    }

    private fun mockChain(request: Request): Interceptor.Chain = mockk {
        every { this@mockk.request() } returns request
    }

    private fun okResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    @Test
    fun `retry policy matrix - only idempotent requests are retried, at most twice`() {
        val retried = listOf(
            request("GET"),
            request("HEAD"),
            request("OPTIONS"),
            request("POST", idempotent = "true"),
        )
        val notRetried = listOf(
            request("POST"),
            // header 值必须**精确**等于 "true"：其它值一律 fail-closed 不重试
            request("POST", idempotent = "false"),
            request("POST", idempotent = "TRUE"),
        )

        retried.forEach { req ->
            val chain = mockChain(req)
            every { chain.proceed(req) } throws IOException("boom")
            assertThrows(IOException::class.java) { interceptor.intercept(chain) }
            // 首次 + 2 次重试
            verify(exactly = 3) { chain.proceed(req) }
        }

        notRetried.forEach { req ->
            val chain = mockChain(req)
            every { chain.proceed(req) } throws IOException("boom")
            assertThrows(IOException::class.java) { interceptor.intercept(chain) }
            verify(exactly = 1) { chain.proceed(req) }
        }
    }

    @Test
    fun `transient IOException then success - stops retrying as soon as it succeeds`() {
        val getRequest = request("GET")
        val chain = mockChain(getRequest)
        val ok = okResponse(getRequest)
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
        val getRequest = request("GET")
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
}
