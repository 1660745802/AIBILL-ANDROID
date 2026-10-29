package com.aibill.android.data.remote.interceptor

import android.app.Application
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 使用真实 TokenManager，proceed 回调控制「请求发出后、旧响应到达前」的会话变更。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthInterceptorTest {
    private lateinit var tokens: TokenManager
    private val events = mockk<AuthEventBus>(relaxed = true)
    private lateinit var interceptor: AuthInterceptor
    private val proceeded = mutableListOf<Request>()

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("auth_interceptor", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokens = TokenManager(prefs)
        interceptor = AuthInterceptor(tokens, events, mockk(relaxed = true))
    }

    private fun chain(code: Int, inFlight: () -> Unit = {}): Interceptor.Chain {
        val original = Request.Builder().url("https://example.invalid/api/transactions").build()
        return mockk {
            every { request() } returns original
            every { proceed(any()) } answers {
                val sent = firstArg<Request>()
                proceeded += sent
                inFlight()
                Response.Builder().request(sent).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").build()
            }
        }
    }

    @Test
    fun `only authenticated current 401 clears session before event`() {
        tokens.saveSession("jwt-old", 7, "alice", "Alice")
        every { events.emit(AuthEvent.TokenExpired) } answers {
            assertNull(tokens.getToken())
            assertEquals(SessionEndReason.TOKEN_EXPIRED, tokens.getSessionEndReason())
        }
        assertEquals(401, interceptor.intercept(chain(401)).code)
        assertEquals("Bearer jwt-old", proceeded.single().header("Authorization"))
        assertNull(tokens.getToken())
        assertEquals(7, tokens.getLastKnownUserId())
        verify(exactly = 1) { events.emit(AuthEvent.TokenExpired) }
    }

    @Test
    fun `non-401 does not clear or emit`() {
        tokens.saveSession("jwt-old", 7, "alice", null)
        val generation = tokens.sessionGeneration()
        listOf(200, 500).forEach { code ->
            assertEquals(code, interceptor.intercept(chain(code)).code)
            assertEquals("Bearer jwt-old", proceeded.last().header("Authorization"))
        }
        assertEquals(generation, tokens.sessionGeneration())
        assertEquals("jwt-old", tokens.getToken())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `unauthenticated 401 never ends or signals a session`() {
        tokens.endSession(SessionEndReason.SIGNED_OUT)
        val generation = tokens.sessionGeneration()
        interceptor.intercept(chain(401))
        assertNull(proceeded.single().header("Authorization"))
        assertEquals(generation, tokens.sessionGeneration())
        assertEquals(SessionEndReason.SIGNED_OUT, tokens.getSessionEndReason())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `unauthenticated old 401 cannot clear a login completed in flight`() {
        interceptor.intercept(chain(401) { tokens.saveSession("jwt-new", 42, "bob", null) })
        assertNull(proceeded.single().header("Authorization"))
        assertEquals("jwt-new", tokens.getToken())
        assertEquals(42, tokens.getLastKnownUserId())
        assertNull(tokens.getSessionEndReason())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `old authenticated 401 cannot clear new account`() {
        tokens.saveSession("jwt-old", 7, "alice", null)
        interceptor.intercept(chain(401) { tokens.saveSession("jwt-new", 42, "bob", null) })
        assertEquals("Bearer jwt-old", proceeded.single().header("Authorization"))
        assertEquals("jwt-new", tokens.getToken())
        assertEquals(42, tokens.getLastKnownUserId())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `same-second identical token relogin still makes old 401 stale`() {
        tokens.saveSession("same-jwt", 7, "alice", null)
        interceptor.intercept(chain(401) { tokens.saveSession("same-jwt", 7, "alice", null) })
        assertEquals("same-jwt", tokens.getToken())
        assertNull(tokens.getSessionEndReason())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `token rotation invalidates old 401 even when jwt string is unchanged`() {
        tokens.saveSession("same-jwt", 7, "alice", null)
        val generation = tokens.sessionGeneration()
        interceptor.intercept(chain(401) { tokens.updateToken("same-jwt", generation) })
        assertEquals("same-jwt", tokens.getToken())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `second old 401 emits only once`() {
        tokens.saveSession("jwt-old", 7, "alice", null)
        interceptor.intercept(chain(401) { interceptor.intercept(chain(401)) })
        assertNull(tokens.getToken())
        assertEquals(SessionEndReason.TOKEN_EXPIRED, tokens.getSessionEndReason())
        verify(exactly = 1) { events.emit(AuthEvent.TokenExpired) }
    }

    @Test
    fun `late 401 after explicit logout must not mark signed out session expired`() {
        tokens.saveSession("jwt-old", 7, "alice", null)
        interceptor.intercept(chain(401) { tokens.endSession(SessionEndReason.SIGNED_OUT) })
        assertEquals(SessionEndReason.SIGNED_OUT, tokens.getSessionEndReason())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `SessionEndReason persisted values are stable`() {
        assertEquals("token_expired", SessionEndReason.TOKEN_EXPIRED.storedValue)
        assertEquals("signed_out", SessionEndReason.SIGNED_OUT.storedValue)
        assertEquals(2, SessionEndReason.entries.size)
    }
}
