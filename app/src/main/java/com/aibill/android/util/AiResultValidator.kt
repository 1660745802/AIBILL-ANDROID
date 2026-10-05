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
 * 5. **金额货币佐证**：原文必须出现货币单位，否则金额可能是从群名/联系人名误取的幻觉
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

        /**
         * 货币单位白名单（用于校验 5 的金额佐证）。
         *
         * 2026-10-01 真实事故：QQ 群通知 `209[嗨] [QQ红包]…：[红包]国庆快乐`
         * 被 AI 抽成 ¥209.00 income 入库并同步服务端——但**原文里根本没有金额**，
         * 209 来自**群名**「209[嗨]」（NLS 把 title 拼到了正文前）。
         *
         * 用法：不校验「金额是否等于原文某处数字」（A1 里 209 确实在原文里，
         * 那个检查抓不到），而是校验原文**有没有货币单位**——真实交易必有，
         * 从名字里抓数字的幻觉通知必无。
         */
        private val CURRENCY_UNITS = listOf("¥", "￥", "元", "人民币", "RMB", "CNY", "块")
    }

    /**
     * 校验单条 AI 解析结果
     *
     * @param sourceText 触发本次解析的**通知/页面原文**。必传——校验 5 依赖它，
     *   传 null 或空串等于关掉幻觉防护，不允许这样调用。
     */
    suspend fun validate(
        amount: Int,
        type: String,
        categoryId: Int?,
        description: String?,
        sourceText: String,
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

        // 5. 金额货币佐证：原文必须出现货币单位
        if (amount > 0 && CURRENCY_UNITS.none { sourceText.contains(it, ignoreCase = true) }) {
            errors.add("原文无货币单位佐证，金额可能从群名/联系人名误取")
        }

        if (errors.isNotEmpty()) {
            Timber.w("AI 结果校验失败: $errors")
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }
}
