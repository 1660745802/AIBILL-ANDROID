package com.aibill.android.domain.repository

import com.aibill.android.domain.model.CategoryRule

/**
 * 分类学习规则仓库
 *
 * 「关键词 → 分类ID」映射的持久化抽象，供 [com.aibill.android.domain.usecase.CategoryLearningEngine] 使用。
 * domain 层仅依赖此接口，不感知 Room 实现细节。
 */
interface CategoryRuleRepository {

    suspend fun findByKeyword(keyword: String): CategoryRule?

    suspend fun getAll(): List<CategoryRule>

    suspend fun upsert(rule: CategoryRule)

    suspend fun incrementHitCount(keyword: String, now: Long = System.currentTimeMillis())

    suspend fun deleteByKeyword(keyword: String)

    suspend fun deleteAll()
}
