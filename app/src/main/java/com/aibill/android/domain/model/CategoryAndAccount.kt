package com.aibill.android.domain.model

/**
 * 分类 Domain Model
 */
data class Category(
    val id: Int,
    val name: String,
    val type: TransactionType,
    val icon: String,
    val sortOrder: Int = 0,
)

/**
 * 账户 Domain Model
 */
data class Account(
    val id: Int,
    val name: String,
    val type: String,
    val icon: String,
    val currentBalance: Int = 0, // 分
)

/**
 * 模板 Domain Model
 * 一键复用的常用交易模板
 */
data class Template(
    val id: Long = 0,
    val name: String,
    val type: TransactionType,
    val amount: Int, // 分
    val categoryId: Int? = null,
    val accountId: Int? = null,
    val description: String? = null,
    val sortOrder: Int = 0,
)
