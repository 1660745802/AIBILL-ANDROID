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
    @Json(name = "sms_packages") val smsPackages: List<String>?
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
    @Json(name = "max_amount_cents") val maxAmountCents: Int?,
    @Json(name = "min_amount_cents") val minAmountCents: Int?
)
