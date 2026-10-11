package com.aibill.android.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.AppLogDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.local.entity.AppLogEntity
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
    ],
    version = 8, // v7 → v8: 删除死表 templates / auto_rules / recurring_rules
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun appLogDao(): AppLogDao

    companion object {
        const val DATABASE_NAME = "aibill.db"
    }
}
