package com.aibill.android.di

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.record.RecordClock
import com.aibill.android.data.record.RecordIntakeImpl
import com.aibill.android.data.record.RecordMoment
import com.aibill.android.data.record.RecordWriter
import com.aibill.android.data.record.RoomRecordWriter
import com.aibill.android.domain.record.RecordIntake
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 入账 module 的装配。
 *
 * ⚠️ [RecordWriter] 的生产绑定是 [RoomRecordWriter]（Room 真事务）；
 * [com.aibill.android.data.record.DirectRecordWriter] 只出现在单测里，**不在此绑定**——
 * 它不提供跨表原子性，误接到生产构建上就是数据不一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object RecordIntakeModule {

    @Provides
    @Singleton
    fun provideRecordWriter(
        database: AppDatabase,
        pendingTransactionDao: PendingTransactionDao,
        notificationRecordDao: NotificationRecordDao,
    ): RecordWriter = RoomRecordWriter(database, pendingTransactionDao, notificationRecordDao)

    /**
     * 系统时钟。抽成接口只为单测能钉死 `date` / `time` / `client_created_at`
     * （否则「泰语 locale 下必须仍是 ISO 日期」这类不变测试只能断言到「今天」）。
     */
    @Provides
    @Singleton
    fun provideRecordClock(): RecordClock = object : RecordClock {
        override fun now() = RecordMoment.system()
    }

    @Provides
    @Singleton
    fun provideRecordIntake(impl: RecordIntakeImpl): RecordIntake = impl
}
