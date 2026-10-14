package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * **已确认记录的本地缓存**（表名 `pending_transactions` 是历史遗留，列也一并不动）。
 *
 * 只在服务端回执（`created[]` 或 `duplicates[]`）按 `client_id` 匹配到 id **之后**才写入
 * （唯一写入口 `data/record/RecordWriter`），因此行 = 服务端已确认。
 * [serverTransactionId] 是撤销时删服务端那一笔的凭据。
 *
 * ## 三个废弃列（**保留列，不写 migration**）
 *
 * [syncStatus] / [retryCount] / [lastError] 属于「待上传队列」语义
 * （排队等上传 / 本地终态 / 重试次数 / 失败原因），随离线队列一起取消：
 * 本应用不再重试、不再有「未同步」这个状态，这三列不再被读取。
 * 唯一的残留用途是 [syncStatus] 标记 `synced`（= 已确认缓存，新写入恒为此值）；
 * 老版本留下的 `pending` / `failed` 行不参与任何判定。
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

    // PR #43：冗余存 name/icon，本地缓存展示不需要 join 分类表
    @ColumnInfo(name = "category_name")
    val categoryName: String? = null,

    @ColumnInfo(name = "category_icon")
    val categoryIcon: String? = null,

    @ColumnInfo(name = "account_id")
    val accountId: Int? = null,

    @ColumnInfo(name = "account_name")
    val accountName: String? = null,

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

    /**
     * 这笔来自哪一次 AI 解析（服务端 `ai_parse_logs.id`）。
     *
     * **已废弃（parse-feedback 已下线，双端一致）**：值仍随 AI 解析落库，但客户端不再读取
     * （历史用途是「从服务端事务 id 反查锚点 → 回传用户改动」，那条链路已删）。
     * 列保留、不写 migration，同 [syncStatus]。
     *
     * null = 非 AI 解析路径（手工记账、外部静默入账）或服务端未返回日志 id
     * （被服务端预筛挡下、上游超时等形态）。
     */
    @ColumnInfo(name = "parse_log_id")
    val parseLogId: Long? = null,

    @ColumnInfo(name = "client_created_at")
    val clientCreatedAt: String,

    /**
     * @deprecated 取值只剩 `synced`（= 已确认缓存）；`pending` / `failed` 是老版本
     * 遗留，不参与任何判定。列保留，见类 KDoc。
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending", // 已废弃：新写入恒为 synced

    @ColumnInfo(name = "server_transaction_id")
    val serverTransactionId: Int? = null,

    /** @deprecated 已无重试机制，列保留，不再写入。 */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    /** @deprecated 已无失败落库，列保留，不再写入。 */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
