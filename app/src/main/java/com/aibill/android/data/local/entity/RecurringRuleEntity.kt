package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // expense / income
    val amount: Int, // 分
    @ColumnInfo(name = "category_id") val categoryId: Int?,
    @ColumnInfo(name = "account_id") val accountId: Int?,
    val description: String?,
    @ColumnInfo(name = "day_of_month") val dayOfMonth: Int,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "last_executed_at") val lastExecutedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
