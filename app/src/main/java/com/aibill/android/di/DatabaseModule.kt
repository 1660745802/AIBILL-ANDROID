package com.aibill.android.di

import android.content.Context
import androidx.room.Room
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
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
            // v5 → v6：PR #43 给 PendingTransactionEntity 加 3 列冗余 name/icon
            .addMigrations(
                androidx.room.migration.Migration(5, 6) { db ->
                    db.execSQL("ALTER TABLE pending_transactions ADD COLUMN category_name TEXT")
                    db.execSQL("ALTER TABLE pending_transactions ADD COLUMN category_icon TEXT")
                    db.execSQL("ALTER TABLE pending_transactions ADD COLUMN account_name TEXT")
                },
                androidx.room.migration.Migration(6, 7) { db ->
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL)")
                },
                // v7 → v8：删掉三张死表 templates / auto_rules / recurring_rules。
                // 这三张表从来没有写入方（TemplateRepository.addLocal 零调用、
                // AutoRuleDao / RecurringDao 零业务调用），DROP 不会丢任何用户数据。
                androidx.room.migration.Migration(7, 8) { db ->
                    db.execSQL("DROP TABLE IF EXISTS templates")
                    db.execSQL("DROP TABLE IF EXISTS auto_rules")
                    db.execSQL("DROP TABLE IF EXISTS recurring_rules")
                }
            )
            // 不允许 fallbackToDestructiveMigration：schema 不匹配时必须显式写 Migration，
            // 否则本地缓存（pending_transactions）会被静默清空，违反 PRD §8.4 数据不丢失
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
    fun provideAppLogDao(db: AppDatabase): com.aibill.android.data.local.dao.AppLogDao =
        db.appLogDao()
}
