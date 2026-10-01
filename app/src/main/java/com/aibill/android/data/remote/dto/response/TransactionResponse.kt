package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TransactionDto(
    @Json(name = "id") val id: Int,
    @Json(name = "client_id") val clientId: String?,
    @Json(name = "type") val type: String,
    @Json(name = "amount") val amount: Int,
    @Json(name = "category_id") val categoryId: Int?,
    @Json(name = "category_name") val categoryName: String?,
    @Json(name = "category_icon") val categoryIcon: String?,
    @Json(name = "account_id") val accountId: Int?,
    @Json(name = "account_name") val accountName: String?,
    @Json(name = "target_account_id") val targetAccountId: Int?,
    @Json(name = "target_account_name") val targetAccountName: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "date") val date: String,
    @Json(name = "time") val time: String?,
    @Json(name = "tags") val tags: String? = null,
    /**
     * 交易来源：manual / ai / app_notification。
     *
     * 可空：后端未返回时为 null，TransactionRepositoryImpl.toDomain() 会 fallback 到 MANUAL。
     * 加字段对旧后端完全兼容（Moshi 忽略缺失字段），但**必须有它**——
     * 否则 TransactionRow 的「⚡自动」标记和首页「自动记账 N 笔」计数
     * 对所有从服务端拉取的数据永远是 0（写死 MANUAL 的历史 bug）。
     */
    @Json(name = "source") val source: String? = null,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "updated_at") val updatedAt: String?
) {
    /** 将 tags JSON字符串解析为 List。后端存储为 "[\"tag1\",\"tag2\"]" 格式 */
    fun tagsList(): List<String> {
        if (tags.isNullOrBlank() || tags == "[]" || tags == "null") return emptyList()
        // 去掉首尾的 [ ] 和引号
        return tags.removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }
}

@JsonClass(generateAdapter = true)
data class CreateTransactionResponse(
    @Json(name = "created") val created: List<TransactionDto>,
    @Json(name = "duplicates") val duplicates: List<TransactionDto>
)
