package com.aibill.android.data.remote.interceptor

import com.aibill.android.data.local.datastore.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态替换 Retrofit 请求的 Base URL
 * 从 DataStore 读取用户配置的服务器地址，替换占位 localhost
 */
@Singleton
class ServerUrlInterceptor @Inject constructor(
    private val userPreferences: UserPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        // 从 DataStore 读取用户配置的服务器地址
        val serverUrl = runBlocking { userPreferences.serverUrl.first() }

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
        // 我们只需要 auth/login 部分
        val relativePath = pathSegments.joinToString("/")

        // 构建新的 URL
        val newUrl = parsedBaseUrl.newBuilder()
            .encodedPath("/${relativePath}")
            .query(originalUrl.query)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
