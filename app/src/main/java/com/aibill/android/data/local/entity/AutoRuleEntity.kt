package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自动规则 Entity
 * 存储用户的免确认规则配置（小额、商户、时间段等）
 */
@Entity(tableName = "auto_rules")
data class AutoRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "rule_type")
    val ruleType: String, // "merchant" / "time_range" 等（保留扩展用）

    @ColumnInfo(name = "value")
    val value: String, // "1000" / "瑞幸" / "12:00-13:00"

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
