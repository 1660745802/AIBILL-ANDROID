package com.aibill.android.data.repository

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.dto.request.LoginRequest
import com.aibill.android.data.remote.dto.request.RegisterRequest
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.User
import com.aibill.android.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val userPreferences: UserPreferences,
) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<User> {
        val result = safeApiCall { authApi.login(LoginRequest(username, password)) }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                tokenManager.saveToken(data.token)
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
        val result = safeApiCall {
            authApi.register(RegisterRequest(username, password, inviteCode, nickname))
        }
        return when (result) {
            is Result.Success -> {
                val data = result.data
                tokenManager.saveToken(data.token)
                userPreferences.setUserInfo(data.user.id, data.user.username, data.user.nickname)
                Result.Success(data.user.toDomain())
            }
            is Result.Error -> result
            is Result.Loading -> result
        }
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
        tokenManager.clearToken()
        userPreferences.clear()
    }

    override fun isLoggedIn(): Boolean = tokenManager.hasToken()

    private fun com.aibill.android.data.remote.dto.response.UserDto.toDomain() = User(
        id = id,
        username = username,
        nickname = nickname,
        role = role,
    )
}
