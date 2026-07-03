package com.aibill.android.di

import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.api.BudgetApi
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.data.remote.api.SettingsApi
import com.aibill.android.data.remote.api.StatsApi
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.interceptor.AuthInterceptor
import com.aibill.android.data.remote.interceptor.RetryInterceptor
import com.aibill.android.data.remote.interceptor.ServerUrlInterceptor
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * 占位 Base URL，实际运行时由 ServerUrlInterceptor 动态替换
     * 用户在设置页面配置的服务器地址存储在 DataStore 中
     */
    private const val PLACEHOLDER_BASE_URL = "http://localhost:3000/api/"

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        serverUrlInterceptor: ServerUrlInterceptor,
        authInterceptor: AuthInterceptor,
        retryInterceptor: RetryInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(serverUrlInterceptor)  // 动态替换 URL（必须第一个）
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideTransactionApi(retrofit: Retrofit): TransactionApi =
        retrofit.create(TransactionApi::class.java)

    @Provides
    @Singleton
    fun provideAiApi(retrofit: Retrofit): AiApi =
        retrofit.create(AiApi::class.java)

    @Provides
    @Singleton
    fun provideStatsApi(retrofit: Retrofit): StatsApi =
        retrofit.create(StatsApi::class.java)

    @Provides
    @Singleton
    fun provideBudgetApi(retrofit: Retrofit): BudgetApi =
        retrofit.create(BudgetApi::class.java)

    @Provides
    @Singleton
    fun provideCategoryApi(retrofit: Retrofit): CategoryApi =
        retrofit.create(CategoryApi::class.java)

    @Provides
    @Singleton
    fun provideSettingsApi(retrofit: Retrofit): SettingsApi =
        retrofit.create(SettingsApi::class.java)
}
