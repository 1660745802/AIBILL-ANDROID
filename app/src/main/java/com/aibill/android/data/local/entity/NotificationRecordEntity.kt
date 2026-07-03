package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 通知监听记录 Entity
 * 记录从系统通知捕获到的支付信息
 */
@Entity(tableName = "notification_records")
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "parsed_amount")
    val parsedAmount: Int? = null, // 单位：分

    @ColumnInfo(name = "parsed_type")
    val parsedType: String? = null,

    @ColumnInfo(name = "parsed_description")
    val parsedDescription: String? = null,

    @ColumnInfo(name = "status")
    val status: String = "raw", // raw / parsed / confirmed / ignored

    @ColumnInfo(name = "linked_client_id")
    val linkedClientId: String? = null,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long = System.currentTimeMillis()
)
