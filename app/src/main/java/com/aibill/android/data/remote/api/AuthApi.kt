package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.request.LoginRequest
import com.aibill.android.data.remote.dto.request.RegisterRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.AuthResponse
import com.aibill.android.data.remote.dto.response.MeResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiResponse<AuthResponse>

    @GET("auth/me")
    suspend fun getCurrentUser(): ApiResponse<MeResponse>

    @PUT("auth/password")
    suspend fun changePassword(@Body request: Map<String, String>): ApiResponse<Unit>
}
