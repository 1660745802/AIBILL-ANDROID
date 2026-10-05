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
import com.aibill.android.service.NotificationProcessor
import com.aibill.android.service.SyncWorker
import kotlinx.coroutines.delay
import timber.log.Timber
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
    private val notificationProcessor: NotificationProcessor,
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
        // 必须在 saveSession 之前读旧 userId——saveSession 会覆盖它。
        // 用 getLastKnownUserId() 而非 getUserId()：401（Token 过期）时 AuthInterceptor 会把
        // 整个 session（含 user_id）清掉，而「Token 过期→重登」正是最需要保住离线队列的场景。
        val previousUserId = tokenManager.getLastKnownUserId()
        val result = safeApiCall { authApi.login(LoginRequest(username, password)) }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                val sameAccount = previousUserId != null && previousUserId == data.user.id
                if (sameAccount) {
                    onSameAccountRelogin(data.user.id)
                } else {
                    // PR #44：首次登录 / 换号 / userId 未知（老版本无 session 字段）→ 保守全清
                    clearLocalCache()
                }
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
                // B2：同账号重登 → 重放因 401 未解析的通知。
                // 换号/首次登录走的是上面的 clearLocalCache 分支，队列已被一起清掉
                // （那是另一个用户的数据，本来就不该重放），所以只在这里调。
                if (sameAccount) {
                    notificationProcessor.replayAuthPending()
                }
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
                // 注册一定是新账号（服务端不允许重名），无脑清
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
     * **同账号**重新登录（典型场景：Token 30 天过期后重登）。
     *
     * 原实现在 login 成功时无条件 [clearLocalCache]，会把用户「断网时记的、还没同步上去的」
     * 账全部物理删除——而 Token 过期恰恰是离线数据最容易堆积的时刻，属于数据丢失。
     * 提交 bd39a2b 的原意是「**换号**清缓存」（见其 commit message），
     * 条件判断漏了，是实现与意图不符。
     *
     * 同账号下各表的正确处置：
     * - pending_transactions：**必须保留**。它是离线队列，且「401 卡住」的记录是
     *   可恢复失败——不重置回 pending 就会永远卡在 failed 状态永不上传
     *   （getAllPending() 只捞 'pending'）。这里精准按 last_error 模式重置，
     *   避免把「业务错误 / 服务端异常 / 超最大重试」这些不可恢复失败也重置成无限重试。
     * - categories / accounts：服务端派生缓存，保留即可（HomeViewModel.refresh() 会重新拉），
     *   清掉反而会造成登录后分类/账户短暂空白
     * - notification_records：本地通知审计记录，保留
     */
    private suspend fun onSameAccountRelogin(userId: Int) {
        val reset = pendingTransactionDao.resetFailedMatching(
            errorPattern = SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN,
        )
        if (reset > 0) {
            Timber.i("Auth: 同账号重登(userId=$userId)，重置 $reset 条 401 卡住的离线记录为待同步")
        }
        // 重置后主动排一次同步：否则要等下一次网络回调 / 用户手动点同步才会上传
        workManagerProvider.scheduleSync()
    }

    /**
     * PR #44：**换号**时清空 pending/categories/accounts/notifications。
     * 仅由「首次登录 / 真正换号 / userId 未知」路径调用；
     * 同账号重登走 [onSameAccountRelogin]，不清 pending（避免丢失离线记账）。
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
        // PR M1：一次性清空 token + session，避免漏删；
        // 登出还要连 last_known_user_id 一起清（显式登出 = 真的换人用了），
        // 保证下次登录必然走「首次登录」路径，不误保留上一个账号的离线队列。
        tokenManager.clearSessionAndIdentity()
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

    override suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        return safeApiCall<Unit> {
            authApi.changePassword(
                mapOf("old_password" to oldPassword, "new_password" to newPassword),
            )
        }
    }
}
