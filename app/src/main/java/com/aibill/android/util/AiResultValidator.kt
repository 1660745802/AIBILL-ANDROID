package com.aibill.android.util

import com.aibill.android.data.local.dao.CategoryDao
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 解析结果二次校验器
 *
 * 准确率优先：AI 输出可能有幻觉，通过规则校验降低误入库风险。
 * 校验失败时标 status="needs_confirm"，不静默入库。
 *
 * 校验规则：
 * 1. 金额范围：0 < amount <= MAX_AMOUNT_CENTS（当前 10,000,000 分 = ¥100,000，与云控一致）
 * 2. 类型必须为 expense/income/transfer
 * 3. categoryId 如果非 null，必须在本地分类表中存在
 * 4. description 长度合理（≤200 字符）
 */
@Singleton
class AiResultValidator @Inject constructor(
    private val categoryDao: CategoryDao,
) {

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
    )

    companion object {
        /**
         * 金额上限：**10 万元**（10,000,000 分）。
         *
         * 必须与云控规则 [com.aibill.android.service.NotificationRulesManager.ProcessorRules.maxAmountCents]
         * 保持一致（scripts/rules.json 的 `processor.max_amount_cents`，当前同为 10000000），
         * 否则会出现「本地校验通过但云控认为超限」或反之的分裂行为。
         *
         * 历史坑：本行曾被误写成注释「100 万分 = 1 万元」+ 报错文案「上限 ¥10,000」，
         * 三处（注释 / 文案 / NOTIFICATION.md）都以为阈值是 ¥10,000，实际是 ¥100,000。
         * 改数值前请先确认云控配置。
         */
        private const val MAX_AMOUNT_CENTS = 10_000_000
        private val VALID_TYPES = setOf("expense", "income", "transfer")
        private const val MAX_DESCRIPTION_LENGTH = 200
    }

    /**
     * 校验单条 AI 解析结果
     */
    suspend fun validate(
        amount: Int,
        type: String,
        categoryId: Int?,
        description: String?,
    ): ValidationResult {
        val errors = mutableListOf<String>()

        // 1. 金额范围校验
        if (amount <= 0) {
            errors.add("金额必须大于 0")
        } else if (amount > MAX_AMOUNT_CENTS) {
            errors.add("金额超过上限 ¥${MAX_AMOUNT_CENTS / 100}")
        }

        // 2. 类型校验
        if (type !in VALID_TYPES) {
            errors.add("类型无效: $type")
        }

        // 3. 分类 ID 校验（如果非空，必须在本地分类表中存在）
        if (categoryId != null) {
            val exists = categoryDao.getById(categoryId) != null
            if (!exists) {
                errors.add("分类 ID 不存在: $categoryId")
            }
        }

        // 4. 描述长度校验
        if (description != null && description.length > MAX_DESCRIPTION_LENGTH) {
            errors.add("描述过长: ${description.length} 字符")
        }

        if (errors.isNotEmpty()) {
            Timber.w("AI 结果校验失败: $errors")
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }
}
