package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分类缓存 Entity
 * 从服务器拉取后缓存到本地，供离线使用
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "type")
    val type: String, // expense / income

    @ColumnInfo(name = "icon")
    val icon: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = System.currentTimeMillis()
)
