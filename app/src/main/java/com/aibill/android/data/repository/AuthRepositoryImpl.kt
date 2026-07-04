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
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.User
import com.aibill.android.domain.repository.AuthRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val userPreferences: UserPreferences,
    private val pendingTransactionDao: PendingTransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val notificationRecordDao: NotificationRecordDao,
    private val syncLock: SyncLock,
    private val workManagerProvider: WorkManagerProvider,
) : AuthRepository {

    /**
     * PR C1：登录/注册前先取消在飞的 SyncWorker 并等待锁释放，
     * 避免循环进行到一半时清缓存/换 token 导致 user A 的交易
     * 用 user B 的 token 写到 user B 的服务端。
     *
     * PR 14：WorkManager 调用抽到 WorkManagerProvider 接口，便于单元测试 mock。
     */
    private suspend fun awaitSyncIdle() {
        workManagerProvider.cancelSyncWorker()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (syncLock.isActive() && System.currentTimeMillis() < deadline) {
            delay(WAIT_INTERVAL_MS)
        }
    }

    override suspend fun login(username: String, password: String): Result<User> {
        // PR C1：登录前等 SyncWorker 跑完（或超时强杀）
        awaitSyncIdle()
        val result = safeApiCall { authApi.login(LoginRequest(username, password)) }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                // PR #44：登录成功/换号后清空旧账号的所有本地缓存
                // 避免新账号看到旧账号的 pending 交易/分类/账户
                clearLocalCache()
                // PR M1：原子写入 token + userId + username + nickname，
                // 避免「token 已写入但 userInfo 还在 DataStore」的不一致窗口
                tokenManager.saveSession(
                    token = data.token,
                    userId = data.user.id,
                    username = data.user.username,
                    nickname = data.user.nickname,
                )
                // DataStore 仍同步一份供 Flow 订阅
                userPreferences.setUserInfo(data.user.id, data.user.username, data.user.nickname)
                Result.Success(data.user.toDomain())
            }
            is Result.Error -> result
            is Result.Loading -> result
        }
    }

    override suspend fun register(
        username: String,
        password: String,
        inviteCode: String,
        nickname: String?
    ): Result<User> {
        // PR C1：注册前同样等 SyncWorker
        awaitSyncIdle()
        val result = safeApiCall {
            authApi.register(RegisterRequest(username, password, inviteCode, nickname))
        }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                clearLocalCache()
                tokenManager.saveSession(
                    token = data.token,
                    userId = data.user.id,
                    username = data.user.username,
                    nickname = data.user.nickname,
                )
                userPreferences.setUserInfo(data.user.id, data.user.username, data.user.nickname)
                Result.Success(data.user.toDomain())
            }
            is Result.Error -> result
            is Result.Loading -> result
        }
    }

    /**
     * PR #44：换号时清空 pending/categories/accounts/notifications。
     * 每次登录/注册成功都会调用，确保会话切换是硬切。
     */
    private suspend fun clearLocalCache() {
        pendingTransactionDao.deleteAll()
        categoryDao.deleteAll()
        accountDao.deleteAll()
        notificationRecordDao.deleteAll()
    }

    override suspend fun validateToken(): Result<User> {
        val result = safeApiCall { authApi.getCurrentUser() }
        return when (result) {
            is Result.Success -> Result.Success(result.data.user.toDomain())
            is Result.Error -> result
            is Result.Loading -> result
        }
    }

    override suspend fun logout() {
        // PR M1：一次性清空 token + session，避免漏删
        tokenManager.clearSession()
        userPreferences.clear()
    }

    override fun isLoggedIn(): Boolean = tokenManager.hasToken()

    private fun com.aibill.android.data.remote.dto.response.UserDto.toDomain() = User(
        id = id,
        username = username,
        nickname = nickname,
        role = role,
    )

    companion object {
        /** PR C1：awaitSyncIdle 等待 sync_lock 释放的最长时限 */
        private const val MAX_WAIT_MS = 3_000L
        private const val WAIT_INTERVAL_MS = 50L
    }
}
