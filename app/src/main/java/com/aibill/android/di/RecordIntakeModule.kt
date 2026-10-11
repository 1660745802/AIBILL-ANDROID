package com.aibill.android.di

import com.aibill.android.data.record.RecordClock
import com.aibill.android.data.record.RecordIntakeImpl
import com.aibill.android.data.record.RecordMoment
import com.aibill.android.domain.record.RecordIntake
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 入账 module 的装配。
 *
 * 入库的跨表写入器（[com.aibill.android.data.queue.RecordWriter]）已随离线队列
 * 一起收进 `di/QueueModule`，入账侧不再直接持有它。
 */
@Module
@InstallIn(SingletonComponent::class)
object RecordIntakeModule {

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
