package com.aibill.android.domain.model

/**
 * 用户 Domain Model
 */
data class User(
    val id: Int,
    val username: String,
    val nickname: String?,
    val role: String,
)
