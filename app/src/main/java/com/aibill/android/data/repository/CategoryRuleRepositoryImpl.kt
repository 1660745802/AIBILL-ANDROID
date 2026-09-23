package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.entity.CategoryRuleEntity
import com.aibill.android.domain.model.CategoryRule
import com.aibill.android.domain.repository.CategoryRuleRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRuleRepositoryImpl @Inject constructor(
    private val dao: CategoryRuleDao,
) : CategoryRuleRepository {

    override suspend fun findByKeyword(keyword: String): CategoryRule? =
        dao.findByKeyword(keyword)?.toDomain()

    override suspend fun getAll(): List<CategoryRule> =
        dao.getAll().map { it.toDomain() }

    override suspend fun upsert(rule: CategoryRule) =
        dao.insertOrUpdate(rule.toEntity())

    override suspend fun incrementHitCount(keyword: String, now: Long) =
        dao.incrementHitCount(keyword, now)

    override suspend fun deleteByKeyword(keyword: String) =
        dao.deleteByKeyword(keyword)

    override suspend fun deleteAll() = dao.deleteAll()

    private fun CategoryRuleEntity.toDomain() = CategoryRule(
        keyword = keyword,
        categoryId = categoryId,
        hitCount = hitCount,
        updatedAt = updatedAt,
    )

    private fun CategoryRule.toEntity() = CategoryRuleEntity(
        keyword = keyword,
        categoryId = categoryId,
        hitCount = hitCount,
        updatedAt = updatedAt,
    )
}
