package com.aibill.android.di

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.record.RecordWriter
import com.aibill.android.data.record.RoomRecordWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 本地缓存跨表写入器的装配。
 *
 * ⚠️ [RecordWriter] 的生产绑定是 [RoomRecordWriter]（Room 真事务）；
 * [com.aibill.android.data.record.DirectRecordWriter] 只出现在单测里，**不在此绑定**——
 * 它不提供跨表原子性，误接到生产构建上就是数据不一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object RecordWriteModule {

    @Provides
    @Singleton
    fun provideRecordWriter(
        database: AppDatabase,
        pendingTransactionDao: PendingTransactionDao,
        notificationRecordDao: NotificationRecordDao,
    ): RecordWriter = RoomRecordWriter(database, pendingTransactionDao, notificationRecordDao)
}
