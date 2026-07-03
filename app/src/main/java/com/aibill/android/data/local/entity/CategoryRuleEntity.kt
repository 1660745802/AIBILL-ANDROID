package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分类学习规则 Entity
 * 存储「关键词 → 分类ID」映射，用于本地智能分类匹配
 */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "keyword")
    val keyword: String,

    @ColumnInfo(name = "category_id")
    val categoryId: Int,

    @ColumnInfo(name = "hit_count")
    val hitCount: Int = 1,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
