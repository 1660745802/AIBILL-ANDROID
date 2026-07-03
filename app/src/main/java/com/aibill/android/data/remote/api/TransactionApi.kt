package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.PaginatedResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import retrofit2.http.*

interface TransactionApi {

    @POST("transactions")
    @Headers("X-Idempotent: true")
    suspend fun createTransactions(
        @Body request: CreateTransactionRequest
    ): ApiResponse<CreateTransactionResponse>

    @GET("transactions")
    suspend fun getTransactions(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("type") type: String? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("account_id") accountId: Int? = null,
        @Query("keyword") keyword: String? = null
    ): ApiResponse<PaginatedResponse<TransactionDto>>

    @GET("transactions/{id}")
    suspend fun getTransaction(@Path("id") id: Int): ApiResponse<TransactionDto>

    @PUT("transactions/{id}")
    suspend fun updateTransaction(
        @Path("id") id: Int,
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): ApiResponse<TransactionDto>

    @DELETE("transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Int): ApiResponse<Any>

    // --- 回收站 ---
    @GET("transactions/trash")
    suspend fun getTrash(): ApiResponse<PaginatedResponse<TransactionDto>>

    @POST("transactions/{id}/restore")
    suspend fun restoreTransaction(@Path("id") id: Int): ApiResponse<Any>

    @DELETE("transactions/{id}/permanent")
    suspend fun permanentDeleteTransaction(@Path("id") id: Int): ApiResponse<Any>
}
