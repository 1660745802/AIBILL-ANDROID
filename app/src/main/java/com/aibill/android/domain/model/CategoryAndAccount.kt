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
