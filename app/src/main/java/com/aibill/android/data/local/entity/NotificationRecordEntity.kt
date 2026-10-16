package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知监听记录 Entity
 * 记录从系统通知捕获到的支付信息
 *
 * 本表只增不减（ARCHITECTURE §12.1#3），因此下面两条每条通知都会跑的热路径查询
 * 必须有复合索引，否则全表扫描会随使用时长线性变慢：
 * - DB 1s 去重：`package_name + content + received_at`（[com.aibill.android.data.local.dao.NotificationRecordDao.findDuplicate]）
 * - 跨渠道判重：`parsed_amount + status + received_at`
 *   （[com.aibill.android.data.local.dao.NotificationRecordDao.findRecentSameAmount]）
 *
 * ⚠️ 索引与 `Migration(8, 9)` 必须严格一致（Room 迁移后会做 schema 校验），
 * 名字由 Room 约定生成：`index_<表名>_<列1>_<列2>...`。
 */
@Entity(
    tableName = "notification_records",
    indices = [
        Index(value = ["package_name", "content", "received_at"]),
        Index(value = ["parsed_amount", "status", "received_at"]),
    ],
)
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
