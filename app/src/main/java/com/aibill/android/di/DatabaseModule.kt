package com.aibill.android.di

import android.content.Context
import androidx.room.Room
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.AutoRuleDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.dao.RecurringDao
import com.aibill.android.data.local.dao.TemplateDao
import com.aibill.android.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            // 不允许 fallbackToDestructiveMigration：schema 不匹配时必须显式写 Migration，
            // 否则未同步的 pending_transactions 会被静默清空，违反 PRD §8.4 数据不丢失
            .build()
    }

    @Provides
    @Singleton
    fun providePendingTransactionDao(db: AppDatabase): PendingTransactionDao =
        db.pendingTransactionDao()

    @Provides
    @Singleton
    fun provideCategoryDao(db: AppDatabase): CategoryDao =
        db.categoryDao()

    @Provides
    @Singleton
    fun provideAccountDao(db: AppDatabase): AccountDao =
        db.accountDao()

    @Provides
    @Singleton
    fun provideNotificationRecordDao(db: AppDatabase): NotificationRecordDao =
        db.notificationRecordDao()

    @Provides
    @Singleton
    fun provideCategoryRuleDao(db: AppDatabase): CategoryRuleDao =
        db.categoryRuleDao()

    @Provides
    @Singleton
    fun provideTemplateDao(db: AppDatabase): TemplateDao =
        db.templateDao()

    @Provides
    @Singleton
    fun provideAutoRuleDao(db: AppDatabase): AutoRuleDao =
        db.autoRuleDao()

    @Provides
    @Singleton
    fun provideRecurringDao(db: AppDatabase): RecurringDao =
        db.recurringDao()
}
