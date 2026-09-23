package com.aibill.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 应用级 [CoroutineScope] 限定符
 *
 * 用于替代 Service / BroadcastReceiver 中手动创建的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，
 * 避免每次实例化创建永不取消的 Scope（进程存活期间持续累积）。
 *
 * 进程级别唯一（@Singleton），由 Hilt 持有，应用生命周期结束后随进程终止而清理。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
