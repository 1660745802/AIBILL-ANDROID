package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NotificationRulesResponse(
    @Json(name = "code") val code: Int,
    @Json(name = "data") val data: NotificationRulesData?
)

@JsonClass(generateAdapter = true)
data class NotificationRulesData(
    @Json(name = "version") val version: Int,
    @Json(name = "updated_at") val updatedAt: String?,
    @Json(name = "rules") val rules: NotificationRulesDto?
)

@JsonClass(generateAdapter = true)
data class NotificationRulesDto(
    @Json(name = "nls") val nls: NlsRulesDto?,
    @Json(name = "a11y") val a11y: A11yRulesDto?,
    @Json(name = "sms") val smsRules: SmsRulesDto?,
    @Json(name = "source_mapping") val sourceMapping: Map<String, String>?,
    @Json(name = "processor") val processor: ProcessorRulesDto?,
    /** 入站预筛判据（跨端契约 §3）。旧服务端没有此键 → null → 客户端行为与今天一致。 */
    @Json(name = "precheck") val precheck: PrecheckDto? = null,
)

/**
 * `precheck` 下发载荷（跨端契约 §3）。字段全部可空：任一缺失都只降级不崩。
 *
 * `bare_amount_max_len` 是 `signals` 的**同层兄弟**（不是 `bare_amount` 对象里的字段）。
 */
@JsonClass(generateAdapter = true)
data class PrecheckDto(
    @Json(name = "schema") val schema: Int?,
    @Json(name = "signals") val signals: PrecheckSignalsDto?,
    @Json(name = "rules") val rules: List<PrecheckRuleDto>?,
)

@JsonClass(generateAdapter = true)
data class PrecheckSignalsDto(
    @Json(name = "money") val money: PrecheckSignalDto?,
    @Json(name = "verb") val verb: PrecheckSignalDto?,
    @Json(name = "bare_amount") val bareAmount: PrecheckSignalDto?,
    @Json(name = "bare_amount_max_len") val bareAmountMaxLen: Int?,
)

/** `pattern` + `flags` 必须成对；缺 `flags` 即该 signal 不可用（见 `PrecheckCompiler`）。 */
@JsonClass(generateAdapter = true)
data class PrecheckSignalDto(
    @Json(name = "pattern") val pattern: String?,
    @Json(name = "flags") val flags: String?,
)

/** `type` = regex（pattern+flags）/ predicate（expr）/ fallback（无字段）。 */
@JsonClass(generateAdapter = true)
data class PrecheckRuleDto(
    @Json(name = "id") val id: String?,
    @Json(name = "tier") val tier: Int?,
    @Json(name = "type") val type: String?,
    @Json(name = "pattern") val pattern: String?,
    @Json(name = "flags") val flags: String?,
    @Json(name = "expr") val expr: String?,
    @Json(name = "desc") val desc: String?,
)

@JsonClass(generateAdapter = true)
data class NlsRulesDto(
    @Json(name = "payment_signal_regex") val paymentSignalRegex: String?,
    @Json(name = "wechat") val wechat: WechatRulesDto?,
    @Json(name = "alipay") val alipay: AlipayRulesDto?,
    @Json(name = "bank_package_patterns") val bankPackagePatterns: List<String>?,
    @Json(name = "sms_packages") val smsPackages: List<String>?,
    @Json(name = "per_package") val perPackage: List<PerPackageRuleDto>?,
    /** 默认规则：未在 per_package 中配置的 App 走这套 */
    @Json(name = "default_rule") val defaultRule: DefaultRuleDto?
)

/**
 * 默认识别规则（大多数 App 共用）。
 * 逻辑：命中排除词直接拒绝；否则含 payment_signal 才放行。
 */
@JsonClass(generateAdapter = true)
data class DefaultRuleDto(
    @Json(name = "exclude_content_contains") val excludeContentContains: List<String>?
)

/**
 * 按包名的通用识别规则（新，可云控）。
 * 匹配到某包名时优先使用此配置，为空则回退到 wechat/alipay/bank 旧逻辑。
 */
@JsonClass(generateAdapter = true)
data class PerPackageRuleDto(
    /** 精确包名匹配 */
    @Json(name = "package") val packageName: String?,
    /** 包名模式匹配（contains/startsWith），用于银行类批量匹配 */
    @Json(name = "package_pattern") val packagePattern: String?,
    /** 命中即全放行（如银行） */
    @Json(name = "pass_all") val passAll: Boolean?,
    /** 放行：title 精确等于 */
    @Json(name = "pass_title_exact") val passTitleExact: List<String>?,
    /** 放行：title 包含 */
    @Json(name = "pass_title_contains") val passTitleContains: List<String>?,
    /** 放行：正文（去掉title后）以这些前缀开头 */
    @Json(name = "pass_msg_prefix") val passMsgPrefix: List<String>?,
    /** 放行：需要含金额符号 */
    @Json(name = "require_amount_symbol") val requireAmountSymbol: Boolean?,
    /** 排除：title 包含（优先级最高，命中直接拒绝） */
    @Json(name = "exclude_title_contains") val excludeTitleContains: List<String>?,
    /** 排除：正文包含 */
    @Json(name = "exclude_content_contains") val excludeContentContains: List<String>?
)

@JsonClass(generateAdapter = true)
data class WechatRulesDto(
    @Json(name = "package_name") val packageName: String?,
    @Json(name = "direct_pass_titles") val directPassTitles: List<String>?,
    @Json(name = "direct_pass_title_contains") val directPassTitleContains: List<String>?,
    @Json(name = "message_prefixes") val messagePrefixes: List<String>?,
    @Json(name = "amount_symbols") val amountSymbols: List<String>?
)

@JsonClass(generateAdapter = true)
data class AlipayRulesDto(
    @Json(name = "package_name") val packageName: String?,
    @Json(name = "allowed_title_keywords") val allowedTitleKeywords: List<String>?
)

@JsonClass(generateAdapter = true)
data class A11yRulesDto(
    @Json(name = "embedded_payment_apps") val embeddedPaymentApps: List<String>?,
    @Json(name = "success_keywords") val successKeywords: List<String>?,
    @Json(name = "embedded_success_keywords") val embeddedSuccessKeywords: List<String>?,
    @Json(name = "common_exclude_keywords") val commonExcludeKeywords: List<String>?,
    @Json(name = "wechat_alipay_exclude_keywords") val wechatAlipayExcludeKeywords: List<String>?,
    @Json(name = "amount_regex") val amountRegex: String?,
    @Json(name = "cooldown_minutes") val cooldownMinutes: Int?,
    /** 列表页关键词（可选；缺省/空数组 → 客户端内置默认）。 */
    @Json(name = "list_page_keywords") val listPageKeywords: List<String>? = null,
    /** 列表页金额数下限（可选；缺失/非正 → 客户端内置默认）。 */
    @Json(name = "list_page_min_amounts") val listPageMinAmounts: Int? = null,
    /** 全局商家标签（服务端 `docs/API.md` 附录 C §10；缺省 → 客户端内置 9 标签）。 */
    @Json(name = "merchant_labels") val merchantLabels: List<String>? = null,
    /** A11Y 摘取 profile 版本（契约 §5）；缺失/≠1 → 客户端忽略整个 app_profiles。 */
    @Json(name = "profiles_schema") val profilesSchema: Int? = null,
    /** per-App 摘取覆盖（契约 §2/§3）；仅 profiles_schema 认识时消费。未知键忽略。 */
    @Json(name = "app_profiles") val appProfiles: Map<String, A11yAppProfileDto>? = null,
)

/**
 * `a11y.app_profiles.<package_name>` 的原始形态（服务端 `docs/API.md` 附录 C §10）。
 *
 * 字段全部可选：`null` = 该键未覆盖，逐键回退到全局（§3.1）。P2 的 `amount_view_id` /
 * `merchant_view_id` 本期**不声明**——未知键 Moshi 静默忽略（§3.9，不 failOnUnknown）。
 */
@JsonClass(generateAdapter = true)
data class A11yAppProfileDto(
    @Json(name = "success_keywords") val successKeywords: List<String>? = null,
    @Json(name = "embedded_success_keywords") val embeddedSuccessKeywords: List<String>? = null,
    @Json(name = "exclude_keywords") val excludeKeywords: List<String>? = null,
    @Json(name = "amount_regex") val amountRegex: String? = null,
    @Json(name = "merchant_labels") val merchantLabels: List<String>? = null,
    @Json(name = "list_page_head_chars") val listPageHeadChars: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SmsRulesDto(
    @Json(name = "spam_keywords") val spamKeywords: List<String>?
)

@JsonClass(generateAdapter = true)
data class ProcessorRulesDto(
    @Json(name = "scoring_window_seconds") val scoringWindowSeconds: Int?,
    @Json(name = "dedup_window_seconds") val dedupWindowSeconds: Int?,
    @Json(name = "marketing_suffix_cutoffs") val marketingSuffixCutoffs: List<String>?,
    @Json(name = "marketing_comma_keywords") val marketingCommaKeywords: List<String>?,
    @Json(name = "max_amount_cents") val maxAmountCents: Int?,
    @Json(name = "min_amount_cents") val minAmountCents: Int?
)
