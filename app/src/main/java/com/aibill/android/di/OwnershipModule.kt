package com.aibill.android.di

import com.aibill.android.data.ownership.LocalDataOwnershipImpl
import com.aibill.android.domain.ownership.LocalDataOwnership
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 本地数据归属 module 的装配（架构候选 4）。
 *
 * 本 module 的依赖（4 个 DAO）全部由既有模块提供，
 * 本文件**只**绑定 `LocalDataOwnership` 这一个 interface。
 */
@Module
@InstallIn(SingletonComponent::class)
object OwnershipModule {

    @Provides
    @Singleton
    fun provideLocalDataOwnership(impl: LocalDataOwnershipImpl): LocalDataOwnership = impl
}
