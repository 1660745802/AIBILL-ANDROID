package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待同步交易 Entity
 * 离线记账时写入本地，联网后同步到服务器
 */
@Entity(tableName = "pending_transactions")
data class PendingTransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_id")
    val clientId: String,

    @ColumnInfo(name = "type")
    val type: String, // expense / income / transfer

    @ColumnInfo(name = "amount")
    val amount: Int, // 单位：分

    @ColumnInfo(name = "category_id")
    val categoryId: Int? = null,

    @ColumnInfo(name = "account_id")
    val accountId: Int? = null,

    @ColumnInfo(name = "target_account_id")
    val targetAccountId: Int? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "date")
    val date: String, // YYYY-MM-DD

    @ColumnInfo(name = "time")
    val time: String? = null, // HH:mm

    @ColumnInfo(name = "tags")
    val tags: String? = null, // JSON 数组字符串

    @ColumnInfo(name = "source")
    val source: String, // manual / ai / app_notification

    @ColumnInfo(name = "source_detail")
    val sourceDetail: String? = null,

    @ColumnInfo(name = "ai_raw_input")
    val aiRawInput: String? = null,

    @ColumnInfo(name = "client_created_at")
    val clientCreatedAt: String,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending", // pending / synced / failed

    @ColumnInfo(name = "server_transaction_id")
    val serverTransactionId: Int? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
