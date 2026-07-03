package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.data.remote.dto.response.BudgetListResponse
import retrofit2.http.*

interface BudgetApi {

    @GET("budgets")
    suspend fun getBudgets(
        @Query("year") year: Int,
        @Query("month") month: Int
    ): ApiResponse<BudgetListResponse>

    @POST("budgets")
    suspend fun createBudget(
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): ApiResponse<BudgetDto>

    @PUT("budgets/{id}")
    suspend fun updateBudget(
        @Path("id") id: Int,
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): ApiResponse<BudgetDto>

    @DELETE("budgets/{id}")
    suspend fun deleteBudget(@Path("id") id: Int): ApiResponse<Unit>
}
