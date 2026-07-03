package com.aibill.android.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * 网络请求重试拦截器
 * - 仅对幂等请求重试（GET/HEAD/OPTIONS 或带 X-Idempotent 头的请求）
 * - 最多重试 3 次，指数退避
 * - 仅重试 IOException（网络层错误），不重试 HTTP 错误
 */
class RetryInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!isRetryable(request)) {
            return chain.proceed(request)
        }

        var lastException: IOException? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                lastException = e
                Timber.w("请求重试 ${attempt + 1}/$MAX_RETRY_COUNT: ${request.url}")
                if (attempt < MAX_RETRY_COUNT - 1) {
                    Thread.sleep(BACKOFF_BASE_MS * (attempt + 1))
                }
            }
        }

        throw lastException ?: IOException("Max retries exceeded")
    }

    private fun isRetryable(request: Request): Boolean {
        return request.method in IDEMPOTENT_METHODS ||
                request.header("X-Idempotent") == "true"
    }

    companion object {
        private const val MAX_RETRY_COUNT = 3
        private const val BACKOFF_BASE_MS = 1000L
        private val IDEMPOTENT_METHODS = listOf("GET", "HEAD", "OPTIONS")
    }
}
