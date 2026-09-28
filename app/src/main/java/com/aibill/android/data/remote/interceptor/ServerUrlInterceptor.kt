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

        // 从缓存原子读 serverUrl（不 runBlocking DataStore）
        // 注：启动竞态兜底在 UserPreferences.getServerUrlBlocking() 真实实现内，
        // 此处不做任何阻塞读取（避免污染单测环境）
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

        // 构建新的 URL：保留 parsedBaseUrl 的完整路径，再追加原始请求路径
        val basePath = parsedBaseUrl.encodedPath.trimEnd('/')
        // 仅当用户配置的 base 路径已包含 api 段时才去掉占位路径的 "api" 前缀
        // （防 /api/api/... 重复）；否则必须保留：后端契约是 /api/*，
        // README 示例 serverUrl 不带 /api，无条件 drop 会把请求打到
        // /auth/login 导致全部 404（历史坑，被测试用例固化）。
        val baseHasApi = basePath.split("/").any { it.equals("api", ignoreCase = true) }
        val pathSegments = originalUrl.pathSegments
        val relativePath = if (baseHasApi && pathSegments.firstOrNull() == "api") {
            pathSegments.drop(1).joinToString("/")
        } else {
            pathSegments.joinToString("/")
        }
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
