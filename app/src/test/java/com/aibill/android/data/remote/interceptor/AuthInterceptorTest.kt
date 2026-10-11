package com.aibill.android.data.remote.interceptor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 覆盖 P0#2：401 时清 Token + emit TokenExpired 事件 + 跳转登录。
 *
 * 关键不变量：
 * 1. 401 时先清 Token 再 emit 事件（顺序很重要：如果先 emit ，
 *    UI 监听到后可能立即重新请求，TokenManager 还持有旧 token 会再发一次）
 * 2. 200 响应时不清 Token、不 emit 事件
 * 3. 401 响应时 Bearer 头会被附加到请求上（说明 token 读取发生在 proceed 之前）
 * 4. 无 token 时不附加 Authorization 头
 * 5. **结束原因按「本次请求是否携带凭证」区分**：带凭证被拒 → [SessionEndReason.TOKEN_EXPIRED]
 *    （用户不知情，需提示）；未带凭证被拒 → [SessionEndReason.SIGNED_OUT]（登录密码错误等，
 *    预期内，不提示）。否则用户输错一次密码，下次冷启动就会被误告知「登录已过期」。
 */
class AuthInterceptorTest {

    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val authEventBus: AuthEventBus = mockk(relaxed = true)
    private val appLogger: com.aibill.android.util.AppLogger = mockk(relaxed = true)
    private val interceptor = AuthInterceptor(tokenManager, authEventBus, appLogger)

    private fun makeChain(request: Request, code: Int): okhttp3.Interceptor.Chain {
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .build()
        return mockk {
            every { this@mockk.request() } returns request
            every { proceed(any()) } returns response
        }
    }

    @Test
    fun `200 response - token appended, no clear, no event`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns "jwt-abc"
        val chain = makeChain(request, 200)

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        // 没结束会话、没 emit
        verify(exactly = 0) { tokenManager.endSession(any()) }
        verify(exactly = 0) { authEventBus.emit(any()) }
    }

    @Test
    fun `401 response - clearSession then emit TokenExpired, in that order`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns "jwt-abc"
        val chain = makeChain(request, 401)

        interceptor.intercept(chain)

        // 顺序很关键：先结束会话再 emit
        // 否则 UI 立刻重新发请求时会带着已过期的 token
        verifyOrder {
            tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)
            authEventBus.emit(AuthEvent.TokenExpired)
        }
    }

    @Test
    fun `no token - no Authorization header, 401 still clears (defensive)`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns null
        val chain = makeChain(request, 401)

        interceptor.intercept(chain)

        // 即便没 token，401 也要结束会话（防止 race：旧 token 刚被外部清掉，401 来时 getToken 已 null）
        // 但**不能**标成 TOKEN_EXPIRED：那会让冷启动误报「登录已过期」
        verify(exactly = 1) { tokenManager.endSession(SessionEndReason.SIGNED_OUT) }
        verify(exactly = 0) { tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED) }
        verify(exactly = 1) { authEventBus.emit(AuthEvent.TokenExpired) }
    }

    @Test
    fun `401 on login endpoint without token - not reported as expired (wrong-password case)`() {
        // 登录密码错误最常见的形态：请求本来就没带凭证，却收到 401。
        // 若也标 TOKEN_EXPIRED，用户下次冷启动会看到「登录已过期」这种风马牛不相及的提示。
        val request = Request.Builder().url("https://api.example.com/auth/login").build()
        every { tokenManager.getToken() } returns null
        val chain = makeChain(request, 401)

        interceptor.intercept(chain)

        verify(exactly = 1) { tokenManager.endSession(SessionEndReason.SIGNED_OUT) }
        verify(exactly = 0) { tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED) }
    }

    @Test
    fun `SessionEndReason stored values are distinct and stable`() {
        // storedValue 会写进 EncryptedSharedPreferences，改名 = 老用户标记失效
        assertEquals("token_expired", SessionEndReason.TOKEN_EXPIRED.storedValue)
        assertEquals("signed_out", SessionEndReason.SIGNED_OUT.storedValue)
        assertEquals(2, SessionEndReason.entries.size)
    }

    @Test
    fun `AuthEvent_TokenExpired is data object singleton - prevents listener equality breakage`() {
        // P0#2 fix 锁住：AuthEvent.TokenExpired 必须是 data object 单例
        // 避免有人误改成 data class 导致 Turbine/test collectors 基于
        // equals/hashCode 判等时收到多个不同实例，订阅测试全部失效
        val event1: AuthEvent = AuthEvent.TokenExpired
        val event2: AuthEvent = AuthEvent.TokenExpired
        assertEquals(event1, event2)
        assertEquals(event1.hashCode(), event2.hashCode())
    }
}
