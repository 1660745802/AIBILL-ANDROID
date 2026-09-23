package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.User

interface AuthRepository {

    suspend fun login(username: String, password: String): Result<User>

    suspend fun register(username: String, password: String, inviteCode: String, nickname: String?): Result<User>

    /** 修改密码 */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit>

    suspend fun validateToken(): Result<User>

    suspend fun logout()

    fun isLoggedIn(): Boolean
}
