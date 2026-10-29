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

        // 凭证和代际必须在同一临界区读取；相同 JWT 字符串不代表相同会话。
        val session = tokenManager.sessionSnapshot()
        val token = session.token?.takeIf { it.isNotBlank() }
        val request = token?.let {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $it")
                .build()
        } ?: originalRequest

        val response = chain.proceed(request)

        // 无凭证/旧请求的 401 不得清掉请求发出后建立的新会话，也不发送过期事件。
        if (response.code == 401 && token != null &&
            tokenManager.endSessionIfCurrent(session.generation, SessionEndReason.TOKEN_EXPIRED)
        ) {
            appLogger.error("AUTH", "401 当前会话已过期，触发跳转登录: url=${originalRequest.url}")
            authEventBus.emit(AuthEvent.TokenExpired)
        }

        return response
    }
}
