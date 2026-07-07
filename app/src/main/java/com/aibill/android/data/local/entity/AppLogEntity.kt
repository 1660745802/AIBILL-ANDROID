package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 应用内日志记录。
 * 持久化到 DB，进程被杀后仍可查看。保留最近 7 天。
 */
@Entity(tableName = "app_logs")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "level") val level: String, // DEBUG / INFO / WARN / ERROR
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "message") val message: String,
)
