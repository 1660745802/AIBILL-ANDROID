package com.aibill.android.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.interceptor.AuthEventBus
import com.aibill.android.data.remote.interceptor.AuthInterceptor
import com.aibill.android.data.remote.interceptor.SessionEndReason
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.model.Result
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** 真实 Retrofit + Moshi codegen + TokenManager；仅在 OkHttp 传输边界返回内存 JSON，零网络。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthPasswordContractTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var tokens: TokenManager
    private val moshi = Moshi.Builder().build()
    private val events = mockk<AuthEventBus>(relaxed = true)
    private lateinit var client: OkHttpClient
    private lateinit var repo: AuthRepositoryImpl
    private val requests = mutableListOf<Request>()
    private var payload = """{"code":0,"data":{"token":"rotated-test-token"},"message":"密码修改成功"}"""
    private var inFlight: () -> Unit = {}
    private var httpCode = 200

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("password_contract", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokens = TokenManager(prefs)
        tokens.saveSession("old-test-token", 7, "alice", "Alice")
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens, events, mockk(relaxed = true)))
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                inFlight()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(httpCode).message("test")
                    .body(payload.toResponseBody("application/json".toMediaType())).build()
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://example.invalid/api/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .validateEagerly(true) // ApiResponse<Unit> 在此即因 Moshi adapter 创建失败而报错。
            .build().create(AuthApi::class.java)
        repo = AuthRepositoryImpl(api, tokens, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), com.aibill.android.data.ownership.CaptureSession())
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `real password response rotates credential preserves identity and next request uses new token`() = runTest {
        val identity = prefs.all.filterKeys { it != "jwt_token" }
        val oldGeneration = tokens.sessionGeneration()
        val result = repo.changePassword("old-fixture-password", "new-fixture-password")
        assertEquals(Result.Success(Unit), result)
        assertEquals("rotated-test-token", tokens.getToken())
        assertEquals(identity, prefs.all.filterKeys { it != "jwt_token" })
        assertTrue(tokens.sessionGeneration() > oldGeneration)

        val sent = requests.single()
        assertEquals("PUT", sent.method)
        assertEquals("/api/auth/password", sent.url.encodedPath)
        assertEquals("Bearer old-test-token", sent.header("Authorization"))
        val body = Buffer().also { requireNotNull(sent.body).writeTo(it) }.readUtf8()
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        assertEquals(
            mapOf("old_password" to "old-fixture-password", "new_password" to "new-fixture-password"),
            moshi.adapter<Map<String, String>>(mapType).fromJson(body),
        )
        repo.changePassword("new-fixture-password", "next-fixture-password")
        assertEquals("Bearer rotated-test-token", requests.last().header("Authorization"))
    }

    @Test
    fun `late password response cannot overwrite another account even if JWT strings match`() = runTest {
        inFlight = { tokens.saveSession("old-test-token", 42, "bob", "Bob") }
        assertTrue(repo.changePassword("old", "new") is Result.Error)
        assertEquals("old-test-token", tokens.getToken())
        assertEquals(42, prefs.getInt("user_id", -1))
        assertEquals("bob", prefs.getString("username", null))
        assertEquals(42, tokens.getLastKnownUserId())
        assertNull(tokens.getSessionEndReason())
        verify(exactly = 0) { events.emit(any()) }
    }

    @Test
    fun `same user re-login also invalidates late password response`() = runTest {
        inFlight = { tokens.saveSession("old-test-token", 7, "alice", "Alice") }
        assertTrue(repo.changePassword("old", "new") is Result.Error)
        assertEquals("old-test-token", tokens.getToken())
    }

    @Test
    fun `logout while password request is in flight cannot be undone by success response`() = runTest {
        inFlight = { tokens.endSession(SessionEndReason.SIGNED_OUT) }
        assertTrue(repo.changePassword("old", "new") is Result.Error)
        assertNull(tokens.getToken())
        assertEquals(SessionEndReason.SIGNED_OUT, tokens.getSessionEndReason())
    }

    @Test
    fun `business error keeps the original session unchanged`() = runTest {
        payload = """{"code":422,"data":null,"message":"旧密码错误"}"""
        val before = prefs.all
        val generation = tokens.sessionGeneration()
        assertEquals(Result.Error(422, "旧密码错误"), repo.changePassword("old", "new"))
        assertEquals(before, prefs.all)
        assertEquals(generation, tokens.sessionGeneration())
    }

    @Test
    fun `malformed and blank replacement token cannot silently succeed`() = runTest {
        val before = prefs.all
        listOf("{}", "null", """{"token":" "}""").forEach { data ->
            payload = """{"code":0,"data":$data,"message":"ok"}"""
            assertTrue(repo.changePassword("old", "new") is Result.Error)
            assertEquals(before, prefs.all)
        }
    }

    @Test
    fun `late HTTP 401 from password endpoint cannot expire a new account`() = runTest {
        httpCode = 401
        payload = """{"code":401,"data":null,"message":"expired"}"""
        inFlight = { tokens.saveSession("new-account-test-token", 42, "bob", "Bob") }
        assertTrue(repo.changePassword("old", "new") is Result.Error)
        assertEquals("new-account-test-token", tokens.getToken())
        verify(exactly = 0) { events.emit(any()) }
    }
}
