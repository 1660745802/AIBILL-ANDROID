package com.aibill.android.domain.notification.rules

import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.notification.A11yProfileOverride
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.PrecheckSpec

/**
 * 通知规则模型（云控下发内容的类型化表示）。
 *
 * C04：从 `service/NotificationRulesManager` 搬到 domain —— 它是「规则这门语言」，
 * 与派生视图 [com.aibill.android.domain.notification.NotificationRuleSnapshot] 同层。
 * 生产调用方**不再直接读它**：一律经 `NotificationRuleSource.snapshot()`（C04 决策 2）。
 * 仅 `data/notification/rules` 的映射与快照派生在内部使用。
 */
data class NotificationRules(
    val nls: NlsRules,
    val a11y: A11yRules,
    val sms: SmsRules,
    val sourceMapping: Map<String, String>,
    val processor: ProcessorRules,
    /** 入站预筛判据（跨端契约 §3）。默认空 = 云控未下发/旧服务端，行为与今天一致。 */
    val precheck: PrecheckSpec = PrecheckSpec.EMPTY,
    /**
     * 云控归表 `prefilter_policy` 的**原始载荷**（`Record<rule_id, 'block'|'forward'|'sample'>`，
     * 跨端契约 §11.2）。默认空 = 旧服务端/旧载荷，行为与今天一致（全部 BLOCK）。
     *
     * 这里保留**字符串**：合法性校验（未知值 / 非契约命名空间）与编译成
     * [PrefilterPolicy] 都发生在快照派生（那里才有 logger 可 warn），
     * 与 `precheck`（raw [PrecheckSpec] → 编译 `PrecheckRules`）同一套分工。
     */
    val prefilterPolicy: Map<String, String> = emptyMap(),
)

data class NlsRules(
    val paymentSignalRegex: String,
    val wechat: WechatRules,
    val alipay: AlipayRules,
    val bankPackagePatterns: List<String>,
    val smsPackages: List<String>,
    val perPackage: List<PerPackageRule> = emptyList(),
    val defaultExcludeContent: List<String> = emptyList(),
)

data class PerPackageRule(
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

data class WechatRules(
    val packageName: String,
    val directPassTitles: List<String>,
    val directPassTitleContains: List<String>,
    val messagePrefixes: List<String>,
    val amountSymbols: List<String>,
)

data class AlipayRules(
    val packageName: String,
    val allowedTitleKeywords: List<String>,
)

data class A11yRules(
    val embeddedPaymentApps: List<String>,
    val successKeywords: List<String>,
    val embeddedSuccessKeywords: List<String>,
    val commonExcludeKeywords: List<String>,
    val wechatAlipayExcludeKeywords: List<String>,
    val amountRegex: String,
    val cooldownMinutes: Int,
    /** 列表页关键词：云控优先，缺省 = [A11yRecognition.DEFAULT_LIST_KEYWORDS]。 */
    val listPageKeywords: List<String> = A11yRecognition.DEFAULT_LIST_KEYWORDS,
    /** 列表页金额数下限：云控优先，缺省 = [A11yRecognition.LIST_PAGE_MIN_AMOUNTS]。 */
    val listPageMinAmounts: Int = A11yRecognition.LIST_PAGE_MIN_AMOUNTS,
    /** 全局商家标签；缺省 = `findMerchant` 原硬编码 9 标签（行为不变）。 */
    val merchantLabels: List<String> = A11yProfileResolver.DEFAULT_MERCHANT_LABELS,
    /** A11Y 摘取 profile 版本；缺失/≠ [A11yProfileResolver.PROFILES_SCHEMA] → 忽略整个 app_profiles。 */
    val profilesSchema: Int? = null,
    /** per-App 摘取覆盖（契约 §2/§3）；仅在 schema 认识时被消费。 */
    val appProfiles: Map<String, A11yProfileOverride> = emptyMap(),
)

data class SmsRules(
    val spamKeywords: List<String>,
)

data class ProcessorRules(
    val scoringWindowSeconds: Int,
    val dedupWindowSeconds: Int,
    val marketingSuffixCutoffs: List<String>,
    val marketingCommaKeywords: List<String>,
    val maxAmountCents: Int,
    val minAmountCents: Int,
)
