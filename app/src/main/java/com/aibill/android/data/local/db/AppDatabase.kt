package com.aibill.android.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.AutoRuleDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.dao.RecurringDao
import com.aibill.android.data.local.dao.TemplateDao
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.local.entity.AutoRuleEntity
import com.aibill.android.data.local.entity.CategoryEntity
import com.aibill.android.data.local.entity.CategoryRuleEntity
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.local.entity.RecurringRuleEntity
import com.aibill.android.data.local.entity.TemplateEntity

@Database(
    entities = [
        PendingTransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        NotificationRecordEntity::class,
        CategoryRuleEntity::class,
        TemplateEntity::class,
        AutoRuleEntity::class,
        RecurringRuleEntity::class,
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun templateDao(): TemplateDao
    abstract fun autoRuleDao(): AutoRuleDao
    abstract fun recurringDao(): RecurringDao

    companion object {
        const val DATABASE_NAME = "aibill.db"
    }
}
