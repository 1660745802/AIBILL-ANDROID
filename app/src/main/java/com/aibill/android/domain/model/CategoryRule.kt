package com.aibill.android.domain.model

/**
 * 分类学习规则（Domain Model）
 *
 * 「关键词 → 分类ID」映射，用于本地智能分类匹配。
 * 持久化细节由 [com.aibill.android.data.repository.CategoryRuleRepositoryImpl] 负责。
 */
data class CategoryRule(
    val keyword: String,
    val categoryId: Int,
    val hitCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
)
