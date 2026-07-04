package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.work.WorkManagerProvider
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.dto.request.LoginRequest
import com.aibill.android.data.remote.dto.request.RegisterRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.AuthResponse
import com.aibill.android.data.remote.dto.response.UserDto
import com.aibill.android.data.remote.interceptor.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 覆盖：
 * - P0#3：register 真接通 AuthApi.register（之前 RegisterScreen 直接跳登录，UI 调用 register 假接口）
 * - P1#44：换号时清空 4 张本地表
 * - M1：saveSession 原子写入 token + userId + username + nickname
 * - C1：登录前 awaitSyncIdle 通过 WorkManagerProvider 取消 SyncWorker
 *
 * 关键不变量：
 * 1. login/register 成功 → clearLocalCache 必清 4 张表
 * 2. login/register 成功 → tokenManager.saveSession 必传 4 个字段
 * 3. login/register 失败 → 不调 saveSession、不调 clearLocalCache
 * 4. clearLocalCache 顺序在 saveSession 之前（避免 user B 拿到 user A 的脏数据）
 * 5. login 前必调 WorkManagerProvider.cancelSyncWorker
 */
class AuthRepositoryImplTest {

    private val authApi: AuthApi = mockk()
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val notificationDao: NotificationRecordDao = mockk(relaxed = true)
    private val syncLock: SyncLock = mockk(relaxed = true)
    private val workManagerProvider: WorkManagerProvider = mockk(relaxed = true)

    private fun makeUserDto(): UserDto = UserDto(
        id = 42,
        username = "alice",
        nickname = "Alice",
        role = "user",
    )

    private fun makeAuthResponse(token: String = "jwt-xyz"): AuthResponse = AuthResponse(
        token = token,
        user = makeUserDto(),
    )

    private fun newRepo(): AuthRepositoryImpl = AuthRepositoryImpl(
        authApi = authApi,
        tokenManager = tokenManager,
        userPreferences = userPreferences,
        pendingTransactionDao = pendingDao,
        categoryDao = categoryDao,
        accountDao = accountDao,
        notificationRecordDao = notificationDao,
        syncLock = syncLock,
        workManagerProvider = workManagerProvider,
    )

    @Test
    fun `login success - clearLocalCache then saveSession with all 4 fields`() = runTest {
        every { syncLock.isActive() } returns false
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        val result = newRepo().login("alice", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        val user = (result as com.aibill.android.domain.model.Result.Success).data
        assertEquals(42, user.id)
        assertEquals("alice", user.username)

        // C1：login 前必调 cancelSyncWorker
        coVerify(exactly = 1) { workManagerProvider.cancelSyncWorker() }

        // P1#44：4 张本地表全部 deleteAll
        coVerify(exactly = 1) { pendingDao.deleteAll() }
        coVerify(exactly = 1) { categoryDao.deleteAll() }
        coVerify(exactly = 1) { accountDao.deleteAll() }
        coVerify(exactly = 1) { notificationDao.deleteAll() }

        // M1：tokenManager.saveSession 收到 4 字段
        coVerifyOrder {
            tokenManager.saveSession(
                token = "jwt-xyz",
                userId = 42,
                username = "alice",
                nickname = "Alice",
            )
        }
    }

    @Test
    fun `login error - no clearLocalCache, no saveSession, return Error`() = runTest {
        every { syncLock.isActive() } returns false
        coEvery { authApi.login(LoginRequest("alice", "wrongpass")) } returns ApiResponse(
            code = 401, data = null, message = "Unauthorized"
        )

        val result = newRepo().login("alice", "wrongpass")

        assertTrue(result is com.aibill.android.domain.model.Result.Error)
        // 关键：错误时不动本地缓存（用户输错密码不至于丢离线交易）
        coVerify(exactly = 0) { pendingDao.deleteAll() }
        coVerify(exactly = 0) { categoryDao.deleteAll() }
        coVerify(exactly = 0) { accountDao.deleteAll() }
        coVerify(exactly = 0) { notificationDao.deleteAll() }
        coVerify(exactly = 0) { tokenManager.saveSession(any(), any(), any(), any()) }
    }

    @Test
    fun `register success - P0#3 fake-register fix, real AuthApi call`() = runTest {
        every { syncLock.isActive() } returns false
        coEvery { authApi.register(RegisterRequest("bob", "pass", "INV-1", "Bob")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(token = "jwt-new"), message = "ok"
        )

        val result = newRepo().register("bob", "pass", "INV-1", "Bob")

        // P0#3 fix：register 真的调用了 AuthApi.register
        coVerify(exactly = 1) { authApi.register(RegisterRequest("bob", "pass", "INV-1", "Bob")) }
        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        coVerify(exactly = 1) { pendingDao.deleteAll() }
        coVerify(exactly = 1) { categoryDao.deleteAll() }
        coVerify(exactly = 1) { accountDao.deleteAll() }
        coVerify(exactly = 1) { notificationDao.deleteAll() }
        coVerify(exactly = 1) {
            tokenManager.saveSession(
                token = "jwt-new",
                userId = 42,
                username = "alice",
                nickname = "Alice",
            )
        }
    }

    @Test
    fun `clearLocalCache called BEFORE saveSession - prevents user A data leak`() = runTest {
        // PR C1/P1#44：清缓存必须在 saveSession 之前，
        // 否则在清缓存和 saveSession 之间，SyncWorker 可能用旧 token 写新表
        every { syncLock.isActive() } returns false
        coEvery { authApi.login(LoginRequest("a", "b")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().login("a", "b")

        // 验证顺序：先清缓存，再写 session
        coVerifyOrder {
            pendingDao.deleteAll()
            tokenManager.saveSession(any(), any(), any(), any())
        }
    }

    @Test
    fun `logout - clearSession (not just clearToken) - M1 atomic`() = runTest {
        // PR M1：登出要清整个 session（token + userId + username + nickname），
        // 之前只 clearToken() 会留下 DataStore 中旧 userInfo
        newRepo().logout()

        // 必须调 clearSession（不是 clearToken）
        coVerify(exactly = 1) { tokenManager.clearSession() }
        // 旧的 clearToken 不应被调
        coVerify(exactly = 0) { tokenManager.clearToken() }
        coVerify(exactly = 1) { userPreferences.clear() }
    }
}
