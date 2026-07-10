package com.aibill.android.data.remote.interceptor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局认证事件
 */
sealed class AuthEvent {
    data object TokenExpired : AuthEvent()
}

/**
 * 认证事件总线（用于 UI 层监听 401 跳转登录）
 */
@Singleton
class AuthEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun emit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Auth 拦截器
 * - 自动附加 JWT Token 到请求头
 * - 401 响应时清除 Token 并触发全局事件
 */
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventBus: AuthEventBus,
    private val appLogger: com.aibill.android.util.AppLogger,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 附加 Token
        val request = tokenManager.getToken()?.let { token ->
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } ?: originalRequest

        val response = chain.proceed(request)

        // 401 全局处理
        if (response.code == 401) {
            appLogger.error("AUTH", "401 Token过期, 触发跳转登录: url=${originalRequest.url}")
            tokenManager.clearToken()
            authEventBus.emit(AuthEvent.TokenExpired)
        }

        return response
    }
}
