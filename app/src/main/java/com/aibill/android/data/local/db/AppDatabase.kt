package com.aibill.android.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.AppLogDao
import com.aibill.android.data.local.dao.CaptureSampleDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.local.entity.AppLogEntity
import com.aibill.android.data.local.entity.CaptureSampleEntity
import com.aibill.android.data.local.entity.CategoryEntity
import com.aibill.android.data.local.entity.CategoryRuleEntity
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity

@Database(
    entities = [
        PendingTransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        NotificationRecordEntity::class,
        CategoryRuleEntity::class,
        AppLogEntity::class,
        CaptureSampleEntity::class,
    ],
    version = 11, // v10 → v11: 新增 capture_samples（被拦内容本地留存，见 CaptureSampleEntity）
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun appLogDao(): AppLogDao
    abstract fun captureSampleDao(): CaptureSampleDao

    companion object {
        const val DATABASE_NAME = "aibill.db"

        /**
         * ⚠️ 迁移链起点（人工确认过的前提，不要凭猜改）：
         *
         * `DatabaseModule.addMigrations` 只注册 5→6 起的迁移。本仓库**首个提交**
         * （8694ec9 `feat:_AIBILL_Android_v0.4`）里 `AppDatabase.version` 就已是 5，
         * 且从未提交过 v5 之前的导出 schema（`/app/schemas/` 被 .gitignore，历史无可查）。
         * 因此这里把「v5 是本项目首个发布 schema」作为前提：DB 版本 < 5 的升级不存在，
         * 也就不需要 1→5 的迁移。
         *
         * 若曾向用户发布过 DB 版本 < 5 的包，升级到本版本时会抛
         * `IllegalStateException: A migration from X to 5 was required but not found`
         * 并**不会**走 destructive fallback（本工程禁止）。
         * 无法从仓库历史证实是否存在 < 5 的已发布包，需人工确认。
         */
    }
}
