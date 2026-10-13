package com.aibill.android.domain.notification

/**
 * 判据的数据源：预筛 module **只认快照，不认云控**。
 *
 * ## 为什么要有这一层
 *
 * 判据本身（哪些词算营销、什么算促销句式、放行顺序）属于 domain；
 * 但它们背后那张词表来自云控 + ETag + 代际缓存，那是 data 层的事实。
 * 不抽这一层，预筛就只能 `@Inject NotificationRulesManager`——那它在单测里就得
 * 造 SharedPreferences / Retrofit / assets 才能跑一次「营销短信被拦」。
 *
 * 两个 adapter：
 * - 生产：`data/notification/CloudNotificationRuleSource`（云控 + 代际缓存）
 * - 单测：`data/notification/InMemoryNotificationRuleSource`（固定规则，零 IO）
 *
 * 与入账 module 的 `RecordWriter` 是同一次「interface + 两个 adapter 换回可测性」的应用。
 */
interface NotificationRuleSource {

    /**
     * 取判据快照。
     *
     * 实现必须保证：**同一代际内多次调用返回同一份内容**（调用方按代际缓存派生值）。
     */
    fun snapshot(): NotificationRuleSnapshot
}

/**
 * 一代判据。构造后不可变，因此派生一次即可被整条判定链无锁读取。
 *
 * @param generation 规则代际（云控更新即自增）。保留它是为了让派生值缓存与规则更新对齐。
 * @param paymentSignal `nls.payment_signal_regex` 编译结果
 * @param marketingWords 营销词表 = `sms.spam_keywords` + `nls.default_rule.exclude_content_contains`
 * @param defaultExcludeContent 判据走 `default_rule` 分支的条件（为空则回退旧硬编码）
 * @param knownPackages 已知支付相关包名（硬编码 + 云控 `source_mapping` + `sms_packages`）
 * @param bankPackagePatterns 银行包名**模式**（无法预展开成包名集合，见 [isKnownSource]）
 * @param perPackage 按包名配置的通用放行/排除规则
 * @param legacy 旧硬编码分支（per_package 与 default_rule 都落空时的向后兼容兜底）
 * @param precheck 服务端下发的入站预筛判据（已编译 Regex）；[PrecheckRules.EMPTY] = 降级放行
 * @param marketingSuffixCutoffs 营销后缀截断词（`processor.marketing_suffix_cutoffs`）——
 *   预筛用它算 precheck 的输入串，与转发给 AI 的 `input` 保持一致
 * @param marketingCommaKeywords 营销词首次出现处截断（`processor.marketing_comma_keywords`）
 * @param a11yListPageKeywords A11Y 列表页关键词（云控优先、内置兜底）
 * @param a11yListPageMinAmounts A11Y 列表页金额数下限（云控优先、内置兜底；预筛对非正值再兜底）
 * @param a11yGlobalProfile 全局 A11Y 摘取配置（amount_regex 已在建快照时编译并兜底）
 * @param a11yPackageProfiles 已逐键回退的 per-App 摘取策略（服务端 `docs/API.md` 附录 C §10；空 = 无 profile，
 *   行为与今天完全一致）。**只在 schema 认识时非空**，且只用于「选/取文本」。
 */
data class NotificationRuleSnapshot(
    val generation: Int,
    val paymentSignal: Regex,
    val marketingWords: List<String>,
    val defaultExcludeContent: List<String>,
    val knownPackages: Set<String>,
    val bankPackagePatterns: List<String>,
    val perPackage: List<PerPackageCriteria>,
    val legacy: LegacyCriteria,
    val precheck: PrecheckRules = PrecheckRules.EMPTY,
    val marketingSuffixCutoffs: List<String> = emptyList(),
    val marketingCommaKeywords: List<String> = emptyList(),
    val a11yListPageKeywords: List<String> = emptyList(),
    val a11yListPageMinAmounts: Int = 0,
    val a11yGlobalProfile: A11yGlobalProfile = A11yGlobalProfile(),
    val a11yPackageProfiles: Map<String, A11yPackageProfile> = emptyMap(),
) {

    /**
     * 是否为已知支付来源。**仅用于观测与熔断作用域，不参与放行/拦截判定**
     * （v6 `default_rule` 的设计承诺：「新增 App 默认自动生效」）。
     */
    fun isKnownSource(packageName: String): Boolean {
        if (packageName in knownPackages) return true
        return bankPackagePatterns.any { pattern ->
            pattern.isNotBlank() && packageName.contains(pattern, ignoreCase = true)
        }
    }

    /** 营销判定：命中营销词表 **且** 不含强交易特征。语义见 [MarketingCriteria]。 */
    fun isMarketing(text: String): Boolean = MarketingCriteria.isMarketingByWords(text, marketingWords)
}

/**
 * 按包名配置的判据（云控 `nls.per_package` 的一行）。
 *
 * 与 `data` 层的 `PerPackageRule` DTO 一一对应，但**不含包名以外的任何来源信息**——
 * 判定链只依赖这里列出的判据字段。
 */
data class PerPackageCriteria(
    val packageName: String? = null,
    val packagePattern: String? = null,
    val passAll: Boolean = false,
    val passTitleExact: List<String> = emptyList(),
    val passTitleContains: List<String> = emptyList(),
    val passMsgPrefix: List<String> = emptyList(),
    val requireAmountSymbol: Boolean = false,
    val excludeTitleContains: List<String> = emptyList(),
    val excludeContentContains: List<String> = emptyList(),
) {
    fun matches(pkg: String): Boolean {
        if (packageName != null && packageName == pkg) return true
        if (packagePattern != null && packagePattern.isNotBlank()) {
            return pkg.contains(packagePattern) || pkg.startsWith(packagePattern)
        }
        return false
    }
}

/**
 * 旧硬编码分支的词表。
 *
 * 仅在 `per_package` 与 `default_rule` 都落空时使用（云控缺字段时的向后兼容兜底），
 * 正常情况下走不到——保留它是为了规则不完整时行为不变，而不是为了新增能力。
 */
data class LegacyCriteria(
    val wechatDirectPassTitles: List<String>,
    val wechatDirectPassTitleContains: List<String>,
    val wechatMessagePrefixes: List<String>,
    val wechatAmountSymbols: List<String>,
    val alipayAllowedTitleKeywords: List<String>,
)
