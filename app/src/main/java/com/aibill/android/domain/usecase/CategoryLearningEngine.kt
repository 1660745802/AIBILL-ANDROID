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
     * PR M5：之前 `input.contains(rule.keyword)` 是裸子串匹配。
     * 例子：用户给「招商银行信用卡还款」标了「信用卡」类别，
     * 之后任意包含「信用卡」的输入（如「广发信用卡积分兑换」）都会误命中。
     *
     * 修复：包含匹配阶段改用近似词边界匹配（见 containsWordBoundary）；
     * 同时按 keyword 长度降序优先匹配（更具体的关键词优先）。
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

        // 2. 包含匹配：按 hitCount DESC + keyword 长度 DESC 优先
        //    （更长的关键词更具体，避免「信用」错误命中「信用卡」规则）
        val allRules = categoryRuleDao.getAll()
            .sortedByDescending { it.keyword.length }
        val containsMatch = allRules.firstOrNull { rule ->
            input.containsWordBoundary(rule.keyword)
        }
        if (containsMatch != null) {
            categoryRuleDao.incrementHitCount(containsMatch.keyword)
            Timber.d("包含匹配: [$input] 命中规则 [${containsMatch.keyword}] → categoryId=${containsMatch.categoryId}")
            return containsMatch.categoryId
        }

        // 3. 无匹配
        return null
    }

    /**
     * PR M5：近似词边界匹配。
     * 中文没有显式词边界，所以采用「keyword 在 input 中所有出现位置，
     * 紧邻字符是边界字符（开头/结尾/非汉字）」。
     * 注意：仅是近似匹配，目标是减少子串误匹配，不追求完美分词。
     */
    private fun String.containsWordBoundary(keyword: String): Boolean {
        if (keyword.isBlank()) return false
        var fromIndex = 0
        while (true) {
            val idx = indexOf(keyword, fromIndex)
            if (idx < 0) return false
            val leftOk = idx == 0 || !this[idx - 1].isChinese()
            val rightOk = idx + keyword.length == length || !this[idx + keyword.length].isChinese()
            if (leftOk && rightOk) return true
            fromIndex = idx + 1
        }
    }

    private fun Char.isChinese(): Boolean =
        this.code in 0x4E00..0x9FFF || this.code in 0x3400..0x4DBF
}
