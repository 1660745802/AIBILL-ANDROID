package com.aibill.android.di

import com.aibill.android.data.undo.RecordUndoImpl
import com.aibill.android.domain.undo.RecordUndo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 撤销 module 的装配（架构候选 5）。
 *
 * 本 module 的依赖（PendingTransactionDao / TransactionRepository / AppLogger）
 * 全部由既有模块提供，本文件**只**绑定 `RecordUndo` 这一个 interface。
 *
 * 接收器（`AutoRecordActionReceiver`）经 `@AndroidEntryPoint` 拿到它，
 * 因此测试不再需要把接收器本身拉起来——平台壳只做管道，判据在这里。
 */
@Module
@InstallIn(SingletonComponent::class)
object UndoModule {

    @Provides
    @Singleton
    fun provideRecordUndo(impl: RecordUndoImpl): RecordUndo = impl
}
