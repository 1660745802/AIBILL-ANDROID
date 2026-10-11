package com.aibill.android.di

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.queue.RecordWriter
import com.aibill.android.data.queue.RoomRecordWriter
import com.aibill.android.data.queue.SyncQueueImpl
import com.aibill.android.domain.queue.SyncQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 离线队列 module 的装配（架构候选 3）。
 *
 * ⚠️ [RecordWriter] 的生产绑定是 [RoomRecordWriter]（Room 真事务）；
 * [com.aibill.android.data.queue.DirectRecordWriter] 只出现在单测里，**不在此绑定**——
 * 它不提供跨表原子性，误接到生产构建上就是数据不一致。
 *
 * 排调度不走本模块的绑定：[SyncQueueImpl] 内部直接调 `SyncScheduler`，
 * 对外只有 [SyncQueue] 一个入口。
 */
@Module
@InstallIn(SingletonComponent::class)
object QueueModule {

    @Provides
    @Singleton
    fun provideRecordWriter(
        database: AppDatabase,
        pendingTransactionDao: PendingTransactionDao,
        notificationRecordDao: NotificationRecordDao,
    ): RecordWriter = RoomRecordWriter(database, pendingTransactionDao, notificationRecordDao)

    @Provides
    @Singleton
    fun provideSyncQueue(impl: SyncQueueImpl): SyncQueue = impl
}
