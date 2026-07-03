package com.aibill.android.domain.usecase

import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.entity.CategoryRuleEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能分类学习引擎
 *
 * 通过记录用户对分类的修正来学习映射规则，
 * 后续遇到相同描述时可跳过 AI 调用直接匹配。
 *
 * 匹配优先级：精确匹配 → 包含匹配 → 无匹配返回 null
 */
@Singleton
class CategoryLearningEngine @Inject constructor(
    private val categoryRuleDao: CategoryRuleDao,
) {

    /**
     * 从用户修正中学习
     * 当用户将某笔交易的描述修改为指定分类时调用
     */
    suspend fun learnFromCorrection(description: String, categoryId: Int) {
        val keyword = description.trim().lowercase()
        if (keyword.isBlank()) return

        val existing = categoryRuleDao.findByKeyword(keyword)
        if (existing != null) {
            // 如果分类相同，仅增加命中次数
            if (existing.categoryId == categoryId) {
                categoryRuleDao.incrementHitCount(keyword)
            } else {
                // 分类改变，覆盖旧规则
                categoryRuleDao.insertOrUpdate(
                    CategoryRuleEntity(
                        keyword = keyword,
                        categoryId = categoryId,
                        hitCount = 1,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            categoryRuleDao.insertOrUpdate(
                CategoryRuleEntity(
                    keyword = keyword,
                    categoryId = categoryId,
                    hitCount = 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        Timber.d("学习分类规则: [$keyword] → categoryId=$categoryId")
    }

    /**
     * 尝试本地匹配分类
     *
     * @return 匹配到的分类 ID，无匹配返回 null
     */
    suspend fun matchCategory(description: String): Int? {
        val input = description.trim().lowercase()
        if (input.isBlank()) return null

        // 1. 精确匹配
        val exactMatch = categoryRuleDao.findByKeyword(input)
        if (exactMatch != null) {
            categoryRuleDao.incrementHitCount(input)
            Timber.d("精确匹配: [$input] → categoryId=${exactMatch.categoryId}")
            return exactMatch.categoryId
        }

        // 2. 包含匹配：遍历所有规则，找到 input 包含的关键词
        val allRules = categoryRuleDao.getAll() // 已按 hitCount DESC 排序
        val containsMatch = allRules.firstOrNull { rule ->
            input.contains(rule.keyword)
        }
        if (containsMatch != null) {
            categoryRuleDao.incrementHitCount(containsMatch.keyword)
            Timber.d("包含匹配: [$input] 命中规则 [${containsMatch.keyword}] → categoryId=${containsMatch.categoryId}")
            return containsMatch.categoryId
        }

        // 3. 无匹配
        return null
    }
}
