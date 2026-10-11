package com.aibill.android.data.repository

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.dto.request.LoginRequest
import com.aibill.android.data.remote.dto.request.RegisterRequest
import com.aibill.android.data.remote.interceptor.SessionEndReason
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.User
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalDataOwnership
import com.aibill.android.domain.repository.AuthRepository
import com.aibill.android.service.NotificationProcessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证与会话。**本地数据的处置不再在这里**：换号 / 同号重登 / 换服务器要动哪几张表、
 * `last_known_user_id` 判成什么，全部归 [LocalDataOwnership]（架构候选 4 收口）。
 *
 * 收口前本类持有 3 个 DAO + 同步队列，与 `ServerConfigViewModel` 各手列一份 4 张表的
 * 清单，靠 KDoc 互保。现在这里只剩**一句意图**，表清单在
 * `data/ownership/LocalDataOwnershipImpl` 一处。
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val userPreferences: UserPreferences,
    private val localDataOwnership: LocalDataOwnership,
    private val notificationProcessor: NotificationProcessor,
) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<User> {
        // 必须在处置 / saveSession 之前问归属——saveSession 会覆盖 last_known_user_id，
        // 之后问到的永远是「刚登进来的这个账号」，同号重登会被误判成换号。
        // 问的是 last_known_user_id（跨 session 存活的归属判据）而非 user_id（session 字段）：
        // 401 时 AuthInterceptor 会把整个 session（含 user_id）清掉，
        // 而「Token 过期 → 重登」正是最需要保住本地缓存的场景。
        val previousOwnership = localDataOwnership.currentOwnership()
        val result = safeApiCall { authApi.login(LoginRequest(username, password)) }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                val outcome = localDataOwnership.dispose(
                    if (previousOwnership.isSameAccount(data.user.id)) {
                        DisposalIntent.SameAccountRelogin
                    } else {
                        // PR #44：换号 / 首次登录 / userId 未知（老版本无 session 字段）→ 保守全清
                        DisposalIntent.SwitchingAccount
                    },
                )
                // PR M1：原子写入 token + userId + username + nickname，
                // 避免「token 已写入但 userInfo 还在 DataStore」的不一致窗口。
                // ⚠️ 必须排在 dispose 之后：反过来就是 user B 的 session 盖在还没清掉的 user A 数据上。
                tokenManager.saveSession(
                    token = data.token,
                    userId = data.user.id,
                    username = data.user.username,
                    nickname = data.user.nickname,
                )
                // DataStore 仍同步一份供 Flow 订阅
                userPreferences.setUserInfo(data.user.id, data.user.username, data.user.nickname)
                // B2：同账号重登 → 重放因 401 未解析的通知。
                // 判据用**处置结果**（Retained = 数据确实留下了）而不是重算一遍归属：
                // 换号/首次登录拿到的是 Cleared，队列已被一起清掉
                // （那是另一个用户的数据，本来就不该重放）。
                if (outcome is DisposalOutcome.Retained) {
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
        val result = safeApiCall {
            authApi.register(RegisterRequest(username, password, inviteCode, nickname))
        }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                // 注册一定是新账号（服务端不允许重名），无脑清
                localDataOwnership.dispose(DisposalIntent.SwitchingAccount)
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
     * 显式登出：**只结束会话，不删本地数据**。
     *
     * - `last_known_user_id` 刻意保留：它是本地缓存的归属判据
     *   （见 [LocalDataOwnership.currentOwnership]）。
     *   若删掉，用户登出再登回同一账号时 `login()` 会判成换号 → 走 `SwitchingAccount`
     *   清空本地缓存，把用户的本地数据物理删除——而 `ProfileScreen` 的
     *   登出确认弹窗向用户承诺「本地数据不会丢失」。
     * - 换号防护不受影响：`login()` 靠 `previousOwnership != incomingUserId` 的**比较**
     *   判定换号，登出后换号依然会清空本地缓存。
     */
    override suspend fun logout() {
        tokenManager.endSession(SessionEndReason.SIGNED_OUT)
        userPreferences.clearUserInfo()
    }

    override fun isLoggedIn(): Boolean = tokenManager.hasToken()

    private fun com.aibill.android.data.remote.dto.response.UserDto.toDomain() = User(
        id = id,
        username = username,
        nickname = nickname,
        role = role,
    )

    override suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        return safeApiCall<Unit> {
            authApi.changePassword(
                mapOf("old_password" to oldPassword, "new_password" to newPassword),
            )
        }
    }
}
