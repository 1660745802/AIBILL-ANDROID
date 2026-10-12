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
                },
                // v8 → v9：给只增不减的 notification_records 补两条热路径复合索引。
                // 每条通知都会跑：package_name+content+received_at（DB 1s 去重）、
                // parsed_amount+status+received_at（跨渠道判重）。
                // ⚠️ 索引名/列序必须与 NotificationRecordEntity 的 @Entity(indices) 严格一致，
                // 否则 Room 迁移后的 schema 校验会抛 IllegalStateException。
                androidx.room.migration.Migration(8, 9) { db ->
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_notification_records_package_name_content_received_at` " +
                            "ON `notification_records` (`package_name`, `content`, `received_at`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_notification_records_parsed_amount_status_received_at` " +
                            "ON `notification_records` (`parsed_amount`, `status`, `received_at`)"
                    )
                },
                // v9 → v10：AI 反馈闭环的本地锚点——记住「这一笔是哪次 AI 解析来的」，
                // 用户改动/删除时才能把纠正回传给服务端（见跨端契约 §2）。
                // 可空：手工/外部静默入账没有 AI 解析，服务端预筛挡下的形态也没有日志 id。
                androidx.room.migration.Migration(9, 10) { db ->
                    db.execSQL("ALTER TABLE pending_transactions ADD COLUMN parse_log_id INTEGER")
                }
            )
            // 迁移链从 5→6 起：前提「v5 是项目首个发布 schema」写在 AppDatabase.companion 注释里。
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
