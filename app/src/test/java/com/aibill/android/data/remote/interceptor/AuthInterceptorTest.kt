package com.aibill.android.data.remote.interceptor

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 覆盖 P0#2：401 时清 Token + emit TokenExpired 事件 + 跳转登录。
 *
 * 关键不变量：
 * 1. 401 时先结束会话再 emit 事件（顺序很重要：如果先 emit，
 *    UI 监听到后可能立即重新请求，TokenManager 还持有旧 token 会再发一次）
 * 2. **只有 401** 结束会话：200/500 都不清 Token、不 emit
 * 3. 有 token 时 Authorization: Bearer 头确实附在 proceed 出去的请求上；
 *    无 token 时不附加
 * 4. **结束原因按「本次请求是否携带凭证」区分**：带凭证被拒 → [SessionEndReason.TOKEN_EXPIRED]
 *    （用户不知情，需提示）；未带凭证被拒 → [SessionEndReason.SIGNED_OUT]（登录密码错误等，
 *    预期内，不提示）。否则用户输错一次密码，下次冷启动就会被误告知「登录已过期」。
 */
class AuthInterceptorTest {

    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val authEventBus: AuthEventBus = mockk(relaxed = true)
    private val appLogger: com.aibill.android.util.AppLogger = mockk(relaxed = true)
    private val interceptor = AuthInterceptor(tokenManager, authEventBus, appLogger)

    /** 实际传给 `chain.proceed` 的请求，用于断言 header 真的被改过。 */
    private val proceeded = mutableListOf<Request>()

    private fun makeChain(request: Request, code: Int): okhttp3.Interceptor.Chain {
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .build()
        return mockk {
            every { this@mockk.request() } returns request
            every { proceed(capture(proceeded)) } returns response
        }
    }

    @Test
    fun `non-401 response - Bearer appended, no session end, no event`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns "jwt-abc"

        listOf(200, 500).forEach { code ->
            val response = interceptor.intercept(makeChain(request, code))

            assertEquals(code, response.code)
            // token 读取发生在 proceed 之前，Authorization 必须真的附上
            assertEquals("Bearer jwt-abc", proceeded.last().header("Authorization"), "code=$code")
            verify(exactly = 0) { tokenManager.endSession(any()) }
            verify(exactly = 0) { authEventBus.emit(any()) }
        }
    }

    @Test
    fun `401 with token - ends session as TOKEN_EXPIRED then emits, in that order`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns "jwt-abc"

        interceptor.intercept(makeChain(request, 401))

        // 顺序很关键：先结束会话再 emit
        // 否则 UI 立刻重新发请求时会带着已过期的 token
        verifyOrder {
            tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)
            authEventBus.emit(AuthEvent.TokenExpired)
        }
        verify(exactly = 0) { tokenManager.endSession(SessionEndReason.SIGNED_OUT) }
    }

    @Test
    fun `401 without token - no Authorization header, ends session as SIGNED_OUT, still emits`() {
        val request = Request.Builder().url("https://api.example.com/transactions").build()
        every { tokenManager.getToken() } returns null

        interceptor.intercept(makeChain(request, 401))

        // 即便没 token，401 也要结束会话（防止 race：旧 token 刚被外部清掉，401 来时 getToken 已 null）
        // 但**不能**标成 TOKEN_EXPIRED：那会让冷启动误报「登录已过期」（登录密码错误即此形态）
        assertNull(proceeded.last().header("Authorization"))
        verify(exactly = 1) { tokenManager.endSession(SessionEndReason.SIGNED_OUT) }
        verify(exactly = 0) { tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED) }
        verify(exactly = 1) { authEventBus.emit(AuthEvent.TokenExpired) }
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
