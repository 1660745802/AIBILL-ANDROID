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
        val token = tokenManager.getToken()
        val request = token?.let {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $it")
                .build()
        } ?: originalRequest

        val response = chain.proceed(request)

        // 401 全局处理：清当前会话（token + userId/username/nickname）。
        // 保留 last_known_user_id 不变 —— 它是本地缓存的归属判据，见 TokenManager.endSession。
        if (response.code == 401) {
            // **只有携带了凭证的 401 才是「会话过期」**。
            // 未携带凭证也被拒（登录密码错误、首次安装未登录）属于预期内，
            // 若也标成 TOKEN_EXPIRED，用户下次冷启动会被误告知「登录已过期」。
            val reason = if (token != null) {
                SessionEndReason.TOKEN_EXPIRED
            } else {
                SessionEndReason.SIGNED_OUT
            }
            appLogger.error("AUTH", "401 会话结束(${reason.storedValue}), 触发跳转登录: url=${originalRequest.url}")
            tokenManager.endSession(reason)
            authEventBus.emit(AuthEvent.TokenExpired)
        }

        return response
    }
}
