package com.aibill.android.domain.notification

/**
 * A11Y 摘取 profile（per-App 覆盖 + 逐键回退）。契约：服务端 `docs/API.md` 附录 C §10（v1，2026-10 冻结）。
 *
 * ## 边界（只做摘取，不做判定）
 * - 只描述**选/取文本**（成功词、排除词、金额正则、商家标签、列表页标题区长度），
 *   **不含任何「是不是账」判定**（原则 1）——profile 不得短路判定链；
 * - 正则即完整规格，客户端**不做任何隐式预处理**（原则 7）；
 * - 逐键回退：`profile[key]` → 全局 `a11y[key]` → 内置默认。profile 未写的键**必须**继续
 *   从全局取，不得因为出现 profile 就丢掉全局值（契约 §3.1）。
 *
 * ## 版本协商与容错（fail-open）
 * 客户端不认识 `profiles_schema`（缺失或 ≠ [A11yProfileResolver.PROFILES_SCHEMA]）→ **忽略整个
 * `app_profiles`**，退回全局键（= 今天行为）。单键不合法（类型不符由 DTO 保证 / regex 编译失败 /
 * 阈值非正）→ 该键回退全局；全局再失败 → 内置兜底。**任何回退都经 [A11yProfileResolver.Warn]
 * 打点（带 package + key）**，否则「profile 没生效」不可观测。
 */

/** 云控 `a11y.app_profiles.<pkg>` 的原始覆盖值；`null` = 该键未覆盖（继续用全局）。 */
data class A11yProfileOverride(
    val successKeywords: List<String>? = null,
    val embeddedSuccessKeywords: List<String>? = null,
    /** 出现即**完全替换**该包排除词；不出现 = 全局作用域默认（契约 §3.3）。 */
    val excludeKeywords: List<String>? = null,
    val amountRegex: String? = null,
    val merchantLabels: List<String>? = null,
    val listPageHeadChars: Int? = null,
)

/** 全局 a11y 配置（逐键回退的基底）。`amountRegex` 已编译（编译失败→内置兜底，只在建快照时做一次）。 */
data class A11yGlobalProfile(
    val successKeywords: List<String> = emptyList(),
    val embeddedSuccessKeywords: List<String> = emptyList(),
    val commonExcludeKeywords: List<String> = emptyList(),
    val wechatAlipayExcludeKeywords: List<String> = emptyList(),
    val amountRegex: Regex = A11yProfileResolver.FALLBACK_AMOUNT_REGEX,
    val merchantLabels: List<String> = A11yProfileResolver.DEFAULT_MERCHANT_LABELS,
)

/** 逐键回退后的最终摘取策略（某包名）。 */
data class A11yPackageProfile(
    /** 已按 embedded 合并的生效成功词。 */
    val successKeywords: List<String>,
    /** 已按 embedded 作用域解析的生效排除词。 */
    val excludeKeywords: List<String>,
    val amountRegex: Regex,
    val merchantLabels: List<String>,
    /** 列表页「标题区」窗口字符数（内置默认 [A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS]）。 */
    val listPageHeadChars: Int,
)

object A11yProfileResolver {

    /** 认识的 `profiles_schema`；缺失或 ≠ 本值 → 整体忽略 `app_profiles`。 */
    const val PROFILES_SCHEMA = 1

    /** 列表页标题区窗口的**内置默认**（契约 §2：全局无此键，只有 profile 可覆盖）。 */
    const val DEFAULT_LIST_PAGE_HEAD_CHARS = 24

    /** 全局 `merchant_labels` 的内置默认 = `findMerchant` 原硬编码 9 标签（保证行为不变）。 */
    val DEFAULT_MERCHANT_LABELS: List<String> =
        listOf("收款方", "商户", "商家", "付款给", "店铺", "商家名", "卖家", "店名", "商品")

    /**
     * `amount_regex` 的**内置兜底**（云控全局也写坏时）。
     * 与 `PaymentAccessibilityService` 原硬编码逐字相同——它是向后兼容下限，不是新判据。
     */
    val FALLBACK_AMOUNT_REGEX = Regex(
        """[¥￥][ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*(\d+\.?\d{0,2})|(\d+\.?\d{0,2})元"""
    )

    /** 回退告警：`(packageName, key, reason)`。schema 级回退的 `packageName` 为空串。 */
    fun interface Warn {
        operator fun invoke(packageName: String, key: String, reason: String)
    }

    /**
     * schema 守卫 + 逐 profile 解析。
     *
     * @param profilesSchema 云控 `a11y.profiles_schema`
     * @param overrides 云控 `a11y.app_profiles`
     * @param embeddedPaymentApps `a11y.embedded_payment_apps`（决定成功词是否并入 embedded 词、排除词作用域）
     */
    fun resolveAll(
        profilesSchema: Int?,
        overrides: Map<String, A11yProfileOverride>,
        embeddedPaymentApps: Set<String>,
        global: A11yGlobalProfile,
        warn: Warn,
    ): Map<String, A11yPackageProfile> {
        if (overrides.isEmpty()) return emptyMap()
        if (profilesSchema != PROFILES_SCHEMA) {
            warn("", "profiles_schema", "不认识（${profilesSchema ?: "缺失"}），忽略全部 app_profiles，退回全局")
            return emptyMap()
        }
        return overrides.entries
            .filter { it.key.isNotBlank() }
            .associate { (pkg, override) ->
                pkg.trim() to resolve(pkg.trim(), pkg.trim() in embeddedPaymentApps, override, global, warn)
            }
    }

    /**
     * 解析单个包名的最终策略（`override == null` 时输出全局默认，供无 profile 的包使用）。
     *
     * 成功词：`profile.success_keywords ?: global.success_keywords`，embedded 包再**并入**
     * `profile.embedded_success_keywords ?: global.embedded_success_keywords`（与改造前一致）。
     * 排除词：profile 写了则**完全替换**；未写则用全局作用域默认
     * （embedded 包 = `common`；微信/支付宝 = `wechat_alipay ⊕ common`）。
     */
    fun resolve(
        packageName: String,
        isEmbedded: Boolean,
        override: A11yProfileOverride?,
        global: A11yGlobalProfile,
        warn: Warn,
    ): A11yPackageProfile {
        val success = override?.successKeywords?.cleaned() ?: global.successKeywords
        val embeddedSuccess = override?.embeddedSuccessKeywords?.cleaned() ?: global.embeddedSuccessKeywords
        val activeSuccess = if (isEmbedded) success + embeddedSuccess else success

        val activeExclude = override?.excludeKeywords?.cleaned()
            ?: if (isEmbedded) global.commonExcludeKeywords
            else global.wechatAlipayExcludeKeywords + global.commonExcludeKeywords

        val amountRegex = resolveAmountRegex(packageName, override?.amountRegex, global.amountRegex, warn)
        val merchantLabels = override?.merchantLabels?.cleaned() ?: global.merchantLabels

        val rawHeadChars = override?.listPageHeadChars
        val headChars = rawHeadChars?.takeIf { it > 0 }
            ?: DEFAULT_LIST_PAGE_HEAD_CHARS.also {
                if (rawHeadChars != null) {
                    warn(packageName, "list_page_head_chars", "非正数（$rawHeadChars），回退内置 $DEFAULT_LIST_PAGE_HEAD_CHARS")
                }
            }

        return A11yPackageProfile(
            successKeywords = activeSuccess,
            excludeKeywords = activeExclude,
            amountRegex = amountRegex,
            merchantLabels = merchantLabels,
            listPageHeadChars = headChars,
        )
    }

    private fun resolveAmountRegex(packageName: String, override: String?, global: Regex, warn: Warn): Regex {
        if (override != null) {
            override.takeIf(String::isNotBlank)
                ?.let { pattern -> runCatching { Regex(pattern) }.getOrNull() }
                ?.let { return it }
            warn(packageName, "amount_regex", "profile 正则编译失败或为空，回退全局")
        }
        return global
    }

    /** trim + 去空项；空列表本身是合法覆盖（表示该包不用这些词）。 */
    private fun List<String>.cleaned(): List<String> = map(String::trim).filter(String::isNotBlank)
}
