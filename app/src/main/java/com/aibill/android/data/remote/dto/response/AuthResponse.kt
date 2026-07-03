package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AuthResponse(
    @Json(name = "token") val token: String,
    @Json(name = "user") val user: UserDto
)

@JsonClass(generateAdapter = true)
data class MeResponse(
    @Json(name = "user") val user: UserDto
)

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: Int,
    @Json(name = "username") val username: String,
    @Json(name = "nickname") val nickname: String?,
    @Json(name = "role") val role: String
)
