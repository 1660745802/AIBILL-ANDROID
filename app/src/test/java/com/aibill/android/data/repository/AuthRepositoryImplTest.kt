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
 * - **同账号重登不清 pending**（数据丢失回归）：Token 过期 → SyncWorker 标 failed →
 *   用户重登同一账号 → 离线记录必须保留并被重置为可重试
 * - M1：saveSession 原子写入 token + userId + username + nickname
 * - C1：登录前 awaitSyncIdle 通过 WorkManagerProvider 取消 SyncWorker
 *
 * 关键不变量：
 * 1. login 成功且**换号/首次**（旧 userId 为 null 或 != 新 userId）→ clearLocalCache 必清 4 张表
 * 2. login 成功且**同账号**（旧 userId == 新 userId）→ **不得** deleteAll，
 *    只把 401 卡住的记录重置为 pending，并排一次同步
 * 3. register 成功 → 必清 4 张表（新账号，无条件）
 * 4. login/register 失败 → 不调 saveSession、不调 clearLocalCache
 * 5. 换号路径下 clearLocalCache 顺序在 saveSession 之前（避免 user B 拿到 user A 的脏数据）
 * 6. login 前必调 WorkManagerProvider.cancelSyncWorker
 */
class AuthRepositoryImplTest {

    private val authApi: AuthApi = mockk()
    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk(relaxed = true)
    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val accountDao: AccountDao = mockk(relaxed = true)
    private val notificationDao: NotificationRecordDao = mockk(relaxed = true)
    private val notificationProcessor: com.aibill.android.service.NotificationProcessor = mockk(relaxed = true)
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
        notificationProcessor = notificationProcessor,
        syncLock = syncLock,
        workManagerProvider = workManagerProvider,
    )

    @Test
    fun `account switch - previous userId differs - clearLocalCache then saveSession with all 4 fields`() = runTest {
        every { syncLock.isActive() } returns false
        // 旧会话是 user 7，新登录拿到 user 42 → 换号
        every { tokenManager.getLastKnownUserId() } returns 7
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

        // P1#44：换号 → 4 张本地表全部 deleteAll（防止 user A 的脏数据进 user B）
        coVerify(exactly = 1) { pendingDao.deleteAll() }
        coVerify(exactly = 1) { categoryDao.deleteAll() }
        coVerify(exactly = 1) { accountDao.deleteAll() }
        coVerify(exactly = 1) { notificationDao.deleteAll() }

        // 换号不走「同账号重登」路径
        coVerify(exactly = 0) { pendingDao.resetFailedMatching(any(), any()) }

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
    fun `DATA LOSS regression - same account re-login does NOT deleteAll pending, resets 401 rows instead`() = runTest {
        // 场景：Token 30 天过期 → SyncWorker 把 3 条离线记录标 failed("Token expired")
        //      → 用户重新登录【同一个账号】(userId 42 → 42)
        // 原实现无条件 clearLocalCache() → pendingTransactionDao.deleteAll()
        // 用户「断网时记的账」被物理删除，不可恢复。
        every { syncLock.isActive() } returns false
        every { tokenManager.getLastKnownUserId() } returns 42
        coEvery { pendingDao.resetFailedMatching(any(), any()) } returns 3
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        val result = newRepo().login("alice", "pass")

        assertTrue(result is com.aibill.android.domain.model.Result.Success)

        // 核心断言：pending 队列绝不能被清空
        coVerify(exactly = 0) { pendingDao.deleteAll() }
        // 同账号下 categories/accounts/notification_records 也保留（server 派生缓存会重新拉）
        coVerify(exactly = 0) { categoryDao.deleteAll() }
        coVerify(exactly = 0) { accountDao.deleteAll() }
        coVerify(exactly = 0) { notificationDao.deleteAll() }

        // 401 卡住的记录必须被重置回 pending，否则 getAllPending() 永远捞不到 → 永不上传
        coVerify(exactly = 1) {
            pendingDao.resetFailedMatching(
                errorPattern = com.aibill.android.service.SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN,
                now = any(),
            )
        }
        // 重置后必须主动排同步，否则要等下一次网络回调
        coVerify(exactly = 1) { workManagerProvider.scheduleSync() }
        // session 照常刷新
        coVerify(exactly = 1) {
            tokenManager.saveSession(
                token = "jwt-xyz", userId = 42, username = "alice", nickname = "Alice",
            )
        }
    }

    @Test
    fun `unknown previous userId (legacy session) - conservatively clearLocalCache`() = runTest {
        // 拿不到旧 userId（老版本没写 session 字段）→ 无法判断是否换号 → 保守清理，
        // 保持 PR #44 的跨账号防护
        every { syncLock.isActive() } returns false
        every { tokenManager.getLastKnownUserId() } returns null
        coEvery { authApi.login(LoginRequest("alice", "pass")) } returns ApiResponse(
            code = 0, data = makeAuthResponse(), message = "ok"
        )

        newRepo().login("alice", "pass")

        coVerify(exactly = 1) { pendingDao.deleteAll() }
        coVerify(exactly = 0) { pendingDao.resetFailedMatching(any(), any()) }
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
    fun `clearLocalCache called BEFORE saveSession on account switch - prevents user A data leak`() = runTest {
        // PR C1/P1#44：换号时清缓存必须在 saveSession 之前，
        // 否则在清缓存和 saveSession 之间，SyncWorker 可能用旧 token 写新表
        every { syncLock.isActive() } returns false
        every { tokenManager.getLastKnownUserId() } returns 7
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

        // 显式登出必须连 last_known_user_id 一起清，
        // 否则下次登录会被误判成「同账号重登」而保留上一个账号的离线队列
        coVerify(exactly = 1) { tokenManager.clearSessionAndIdentity() }
        // 旧的 clearToken 不应被调
        coVerify(exactly = 0) { tokenManager.clearToken() }
        coVerify(exactly = 1) { userPreferences.clear() }
    }
}
