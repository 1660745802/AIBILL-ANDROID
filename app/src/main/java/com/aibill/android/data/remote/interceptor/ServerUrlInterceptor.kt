package com.aibill.android.data.remote.interceptor

import com.aibill.android.data.local.datastore.UserPreferences
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态替换 Retrofit 请求的 Base URL
 * 从 UserPreferences 缓存读取用户配置的服务器地址，替换占位 localhost
 *
 * 注：使用同步 getter（由 UserPreferences init 块中的热流维护 AtomicReference），
 * 避免每次请求 runBlocking DataStore 阻塞 OkHttp 调度线程。
 */
@Singleton
class ServerUrlInterceptor @Inject constructor(
    private val userPreferences: UserPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // 从缓存原子读 serverUrl（不再 runBlocking DataStore）
        val serverUrl = userPreferences.getServerUrlBlocking()

        if (serverUrl.isNullOrBlank()) {
            // 未配置服务器，直接放行（会连不上，但不崩溃）
            return chain.proceed(originalRequest)
        }

        // 解析用户配置的 URL
        val baseUrl = "$serverUrl/".replace("//", "/").replace(":/", "://")
        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()

        if (parsedBaseUrl == null) {
            Timber.w("无法解析服务器地址: $serverUrl")
            return chain.proceed(originalRequest)
        }

        // 提取原始请求中的路径部分（去掉占位 base 的 /api/ 前缀）
        val pathSegments = originalUrl.pathSegments
        // 原始 URL 类似 http://localhost:3000/api/auth/login
        // pathSegments = ["api", "auth", "login"]
        // 需要去掉占位 baseUrl 中的 "api" 前缀，只保留 "auth/login"
        // 因为用户配置的 serverUrl 已包含 /api
        val relativePath = if (pathSegments.firstOrNull() == "api") {
            pathSegments.drop(1).joinToString("/")
        } else {
            pathSegments.joinToString("/")
        }

        // 构建新的 URL：保留 parsedBaseUrl 的完整路径，再追加 relativePath
        val basePath = parsedBaseUrl.encodedPath.trimEnd('/')
        val newUrl = parsedBaseUrl.newBuilder()
            .encodedPath("$basePath/$relativePath")
            .query(originalUrl.query)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
