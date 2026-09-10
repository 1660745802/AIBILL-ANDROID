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
    @Json(name = "processor") val processor: ProcessorRulesDto?
)

@JsonClass(generateAdapter = true)
data class NlsRulesDto(
    @Json(name = "payment_signal_regex") val paymentSignalRegex: String?,
    @Json(name = "wechat") val wechat: WechatRulesDto?,
    @Json(name = "alipay") val alipay: AlipayRulesDto?,
    @Json(name = "bank_package_patterns") val bankPackagePatterns: List<String>?,
    @Json(name = "sms_packages") val smsPackages: List<String>?,
    @Json(name = "per_package") val perPackage: List<PerPackageRuleDto>?
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
    @Json(name = "cooldown_minutes") val cooldownMinutes: Int?
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
