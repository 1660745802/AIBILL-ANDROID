package com.aibill.android.domain.usecase

import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.datastore.UserPreferences
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能免确认建议器
 *
 * 仅基于关键词学习历史判断（准确率优先）：
 * - hitCount >= threshold → 建议免确认
 * - 用户设置 automationLevel 影响判断阈值
 */
@Singleton
class AutoConfirmSuggester @Inject constructor(
    private val categoryRuleDao: CategoryRuleDao,
    private val userPreferences: UserPreferences,
) {

    companion object {
        private const val THRESHOLD_CONSERVATIVE = Int.MAX_VALUE // 保守：永不自动
        private const val THRESHOLD_STANDARD = 3 // 标准：3次
        private const val THRESHOLD_AGGRESSIVE = 1 // 激进：1次
    }

    /**
     * 判断某关键词是否建议免确认
     */
    suspend fun suggestAutoConfirm(keyword: String): Boolean {
        val level = userPreferences.automationLevel.first()
        val threshold = when (level) {
            "conservative" -> THRESHOLD_CONSERVATIVE
            "aggressive" -> THRESHOLD_AGGRESSIVE
            else -> THRESHOLD_STANDARD
        }

        val rule = categoryRuleDao.findByKeyword(keyword.trim().lowercase())
        val suggest = rule != null && rule.hitCount >= threshold

        Timber.d("AutoConfirm: keyword=[$keyword], hitCount=${rule?.hitCount}, threshold=$threshold, suggest=$suggest")
        return suggest
    }

    /**
     * 综合判断是否应该免确认
     * 仅基于关键词学习历史判断（准确率优先，不基于金额盲猜）
     */
    suspend fun shouldAutoConfirm(keyword: String?, amountCents: Int): Boolean {
        // 关键词免确认（基于 CategoryLearningEngine 的 hitCount 积累）
        if (keyword != null && suggestAutoConfirm(keyword)) return true

        return false
    }
}
