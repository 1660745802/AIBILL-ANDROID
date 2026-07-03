package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.AccountListResponse
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CategoryListResponse
import retrofit2.http.*

interface CategoryApi {

    @GET("categories")
    suspend fun getCategories(): ApiResponse<CategoryListResponse>

    @POST("categories")
    suspend fun createCategory(@Body request: @JvmSuppressWildcards Map<String, Any>): ApiResponse<Any>

    @PUT("categories/{id}")
    suspend fun updateCategory(
        @Path("id") id: Int,
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): ApiResponse<Any>

    @DELETE("categories/{id}")
    suspend fun deleteCategory(@Path("id") id: Int): ApiResponse<Unit>

    @GET("accounts")
    suspend fun getAccounts(): ApiResponse<AccountListResponse>

    @POST("accounts")
    suspend fun createAccount(@Body request: @JvmSuppressWildcards Map<String, Any>): ApiResponse<Any>

    @PUT("accounts/{id}")
    suspend fun updateAccount(
        @Path("id") id: Int,
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): ApiResponse<Any>

    @DELETE("accounts/{id}")
    suspend fun deleteAccount(@Path("id") id: Int): ApiResponse<Unit>
}
