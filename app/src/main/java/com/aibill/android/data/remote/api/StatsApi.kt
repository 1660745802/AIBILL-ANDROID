package com.aibill.android.data.remote.api

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CategoryStatListResponse
import com.aibill.android.data.remote.dto.response.StatsSummaryDto
import com.aibill.android.data.remote.dto.response.TrendListResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface StatsApi {

    @GET("stats/summary")
    suspend fun getSummary(
        @Query("year") year: Int,
        @Query("month") month: Int
    ): ApiResponse<StatsSummaryDto>

    @GET("stats/by-category")
    suspend fun getByCategory(
        @Query("year") year: Int,
        @Query("month") month: Int,
        @Query("type") type: String
    ): ApiResponse<CategoryStatListResponse>

    @GET("stats/trend")
    suspend fun getTrend(
        @Query("year") year: Int,
        @Query("month") month: Int,
        @Query("period") period: String,
        @Query("type") type: String
    ): ApiResponse<TrendListResponse>
}
