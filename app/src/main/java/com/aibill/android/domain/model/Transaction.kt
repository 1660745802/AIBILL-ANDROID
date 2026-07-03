package com.aibill.android.domain.model

/**
 * 交易类型
 */
enum class TransactionType(val value: String) {
    EXPENSE("expense"),
    INCOME("income"),
    TRANSFER("transfer");

    companion object {
        fun fromValue(value: String): TransactionType =
            entries.first { it.value == value }
    }
}

/**
 * 交易来源
 */
enum class TransactionSource(val value: String) {
    MANUAL("manual"),
    AI("ai"),
    APP_NOTIFICATION("app_notification");
}

/**
 * 交易 Domain Model
 */
data class Transaction(
    val id: Int? = null,
    val clientId: String,
    val type: TransactionType,
    val amount: Int, // 分
    val categoryId: Int? = null,
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val accountId: Int? = null,
    val accountName: String? = null,
    val targetAccountId: Int? = null,
    val targetAccountName: String? = null,
    val description: String? = null,
    val date: String, // YYYY-MM-DD
    val time: String? = null,
    val tags: List<String>? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val createdAt: String? = null,
)

/**
 * AI 解析结果
 */
data class AiParseResult(
    val type: TransactionType,
    val amount: Int, // 分
    val categoryId: Int,
    val categoryName: String,
    val categoryIcon: String,
    val description: String? = null,
    val date: String,
    val accountId: Int? = null,
    val accountName: String? = null,
    val targetAccountId: Int? = null,
    val targetAccountName: String? = null,
)
