package com.aibill.android.domain.usecase

import com.aibill.android.data.local.dao.AutoRuleDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.datastore.UserPreferences
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能免确认建议器
 *
 * 渐进策略：
 * - hitCount >= 3 → 建议免确认
 * - 用户设置 automationLevel 影响判断阈值
 * - 小额免确认独立判断
 */
@Singleton
class AutoConfirmSuggester @Inject constructor(
    private val categoryRuleDao: CategoryRuleDao,
    private val autoRuleDao: AutoRuleDao,
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
     * 判断小额是否免确认
     */
    suspend fun isSmallAmountAutoConfirm(amountCents: Int): Boolean {
        val level = userPreferences.automationLevel.first()
        if (level == "conservative") return false

        val threshold = userPreferences.smallAmountThreshold.first()
        val enabledRule = autoRuleDao.findRule("small_amount", threshold.toString())
        val isEnabled = enabledRule?.isEnabled ?: false

        return isEnabled && amountCents <= threshold
    }

    /**
     * 综合判断是否应该免确认
     */
    suspend fun shouldAutoConfirm(keyword: String?, amountCents: Int): Boolean {
        // 小额免确认
        if (isSmallAmountAutoConfirm(amountCents)) return true

        // 关键词免确认
        if (keyword != null && suggestAutoConfirm(keyword)) return true

        return false
    }
}
