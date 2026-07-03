package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.User

interface AuthRepository {

    suspend fun login(username: String, password: String): Result<User>

    suspend fun register(username: String, password: String, inviteCode: String, nickname: String?): Result<User>

    suspend fun validateToken(): Result<User>

    suspend fun logout()

    fun isLoggedIn(): Boolean
}
