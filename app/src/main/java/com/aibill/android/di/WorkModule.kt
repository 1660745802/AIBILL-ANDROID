package com.aibill.android.di

import com.aibill.android.data.local.work.DefaultWorkManagerProvider
import com.aibill.android.data.local.work.WorkManagerProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PR 14：WorkManagerProvider 接口 → DefaultWorkManagerProvider 实现
 * 的 Hilt binding。AuthRepositoryImpl 依赖此接口，便于单元测试 mock。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {
    @Binds
    @Singleton
    abstract fun bindWorkManagerProvider(impl: DefaultWorkManagerProvider): WorkManagerProvider
}
