package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 账户缓存 Entity
 * 从服务器拉取后缓存到本地
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "icon")
    val icon: String,

    @ColumnInfo(name = "current_balance")
    val currentBalance: Int = 0, // 单位：分

    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long = System.currentTimeMillis()
)
