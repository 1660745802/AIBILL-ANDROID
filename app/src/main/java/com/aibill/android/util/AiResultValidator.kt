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
 * 5. **金额货币佐证**：AI 给出的金额必须能在原文中找到**带货币单位的对应数字**
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
         * 货币单位白名单 + 佐证数字（用于校验 5 的金额佐证）。
         *
         * 2026-10-01 真实事故：QQ 群通知 `209[嗨] [QQ红包]…：[红包]国庆快乐`
         * 被 AI 抽成 ¥209.00 income 入库并同步服务端——但**原文里根本没有金额**，
         * 209 来自**群名**「209[嗨]」（NLS 把 title 拼到了正文前）。
         *
         * ## 为什么是「带单位的数字」而不是「有没有货币单位」
         *
         * A1 事故时用的判据是「原文含任意货币单位」，有效但**太粗**：
         * 它只问「全文有没有出现过货币单位」，不问「**AI 抽出的这个金额**
         * 对不对得上原文里**带单位**的那个数字」。于是下面这条照样穿过：
         *
         * ```
         * 🧧双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉
         *                                                        ↑ 有「元」
         * ```
         * 全文有「元」（0元兑）→ 旧判据放行 → AI 把促销句式「花3得29」的
         * **3** 当成金额返回 ¥3.00（实测 2026-10-09 21:04，com.netease.yanxuan）。
         *
         * 改判据后：原文里**带货币单位**的数字只有 `0`（0元），而 AI 给的是 300
         * → 300 ∉ {0} → 无佐证 → `isComplete=false` → 进待审池（不丢数据）。
         *
         * ## ⚠️ 收紧不能引入新误杀（2026-10-09 code review 发现并修补）
         *
         * 首版只认「数字紧邻元/¥」，结果把两类**旧判据下能正常入账**的通知
         * 降级成了待审，且都属于高价值场景：
         *
         * | 原文 | 旧判据 | 首版新判据 | 根因 |
         * |---|---|---|---|
         * | `工资到账 1.2万元` | 放行 | ❌ 待审 | 「万」夹在数字与「元」之间 |
         * | `消费５０．００元` | 放行 | ❌ 待审 | `\d` 在 java.util.regex 默认只匹 ASCII |
         *
         * 工资到账是记账 App 的核心场景，且金额大——恰恰是最不该被静默降级的。
         * 所以下面两条 pattern 各自多了一种形态：万位单位、全角数字。
         *
         * 另：拒绝「先收窄、宁可漏放」的辩解——漏放只是多调一次 AI（被校验 5 兜住），
         * 误杀却是**用户可见的真交易被降级**。两者代价不对等，不能用前者换后者。
         */
        private val CURRENCY_AMOUNT_PATTERNS = listOf(
            // 数字在前、单位在后：20.00元 / 1.80 元 / 5.00块 / 100人民币
            // 数字部分先试千分位形式（1,234.56），再试普通整数（1000.00）。
            // ⚠️ 顺序不能反：先写 \d+ 会让「1,234.56」只匹配到「234.56」。
            // ⚠️ 也不能只用 \d{1,3}(?:,\d{3})* —— 那样「1000.00元」匹配不到
            //    整段，退化成匹配尾部「0.00」，把 100000 分误判为无佐证。
            Regex("""(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s*(万|亿)?\s*(?:元|块|人民币)"""),
            // 单位在前、数字在后：¥82.00 / ￥20.00 / 人民币20.00 / RMB 50.00 / CNY 30
            Regex(
                """(?:¥|￥|人民币|RMB|CNY)\s*(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
                RegexOption.IGNORE_CASE,
            ),
        )

        /**
         * 「万/亿」倍数。佐证数字带「万」时（如 `1.2万元`）要乘回去，
         * 否则 12000 分永远对不上 AI 按元解析出的 1200000 分。
         */
        private val MULTIPLIERS = mapOf('万' to 10_000, '亿' to 100_000_000L)

        /**
         * 全角数字 → 半角。
         *
         * `\d` 在 java.util.regex（未开 `UNICODE_CHARACTER_CLASS`）下**只匹 ASCII [0-9]**，
         * 所以 `消费５０．００元` 在收紧后抽不出佐证数字。真机银行/短信里
         * 全角数字并非没见过（部分 ROM 的通知格式化会产出全角）。
         */
        private fun normalizeFullWidth(text: String): String = buildString(text.length) {
            for (ch in text) {
                append(
                    when {
                        ch in '０'..'９' -> '0' + (ch - '０')
                        ch == '．' -> '.'
                        ch == '，' -> ','
                        ch == '￥' -> '¥'
                        else -> ch
                    }
                )
            }
        }
    }

    /**
     * 抽出原文中所有**带货币单位**的数字，统一转成分。
     *
     * 全整数运算：解析阶段直接拆成「元 / 角分」两部分拼成 Int，
     * 不经过 Double（项目硬性规范：金额不得用浮点参与计算）。
     */
    private fun currencyAmountsInCents(sourceText: String): Set<Int> {
        if (sourceText.isBlank()) return emptySet()
        val normalized = normalizeFullWidth(sourceText)
        val result = mutableSetOf<Int>()
        CURRENCY_AMOUNT_PATTERNS.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val base = toCents(match.groupValues[1]) ?: return@forEach
                // 万/亿：「万」在数字与「元」之间，量词落在 group 2
                val unit = match.groupValues.getOrNull(2)?.firstOrNull()
                val multiplier = unit?.let { MULTIPLIERS[it] }
                val value = if (multiplier == null) {
                    base
                } else {
                    val scaled = base.toLong() * multiplier
                    if (scaled > Int.MAX_VALUE) return@forEach
                    scaled.toInt()
                }
                result.add(value)
            }
        }
        return result
    }

    /**
     * `"1,234.56"` → `123456`；`"20"` → `2000`；非数字或**超 Int 上限** → null。
     *
     * ## ⚠️ 乘法必须在 Long 里做（2026-10-09 kiro review 抓到的假修复）
     *
     * 这个函数曾经用 `yuan * 100 + fen`（Int 运算），只在**调用方**的万/亿放大处
     * 做了上界检查。kiro 指出那是**无效保护**：溢出发生在更早的这一步，调用方看到的
     * 已经是回绕后的值，`scaled > Int.MAX_VALUE` 永远不会为真。
     *
     * 实测：`toCents("43000000")` 用 Int 算出 `5032704`（¥50,327.04）——
     * **回绕成了一个落在合法金额区间内的小正数**，于是「43000000元」能为
     * 5032704 分提供佐证。只要 AI 恰好返回该值，校验 1 和校验 5 就会同时通过。
     * （可利用性接近于零，需要 AI 精确回绕值，但不变式一旦写在注释里就该真的成立。）
     *
     * 正确做法：**在产生值的那一层**用 Long 运算并判上界，溢出一律 `null`（丢弃）。
     * 丢弃是安全的：一个抽不出佐证的通知只会进待审；而回绕出的垃圾值可能
     * 变成假放行。
     */
    private fun toCents(raw: String): Int? {
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        val dot = cleaned.indexOf('.')
        val yuanPart = if (dot < 0) cleaned else cleaned.substring(0, dot)
        val fenPart = if (dot < 0) "" else cleaned.substring(dot + 1)
        val yuan = yuanPart.toLongOrNull() ?: return null
        // ".5" 补成 "50"，"20." 补成 "00"，超两位截断
        val fen = fenPart.take(2).padEnd(2, '0').toLongOrNull() ?: 0L
        val total = yuan * 100 + fen
        return if (total > Int.MAX_VALUE) null else total.toInt()
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

        // 5. 金额货币佐证：AI 的金额必须对得上原文里某个**带货币单位**的数字。
        //    失败不删数据，只降级为待审（isComplete=false）。
        if (amount > 0) {
            val corroborated = currencyAmountsInCents(sourceText)
            if (amount !in corroborated) {
                val shown = if (corroborated.isEmpty()) {
                    "无"
                } else {
                    corroborated.sorted().joinToString(",") { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }
                }
                errors.add("金额无货币单位佐证：原文带单位金额=[$shown]，AI 给出 ${amount / 100}.${(amount % 100).toString().padStart(2, '0')}")
            }
        }

        if (errors.isNotEmpty()) {
            Timber.w("AI 结果校验失败: $errors")
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }
}
