package com.aibill.android.data.notification.rules

import com.aibill.android.data.remote.dto.response.A11yAppProfileDto
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.PrecheckDto
import com.aibill.android.data.remote.dto.response.PrecheckSignalDto
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.notification.A11yProfileOverride
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckSignalSpec
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.PerPackageRule
import com.aibill.android.domain.notification.rules.ProcessorRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.WechatRules

/**
 * 云控 DTO → 规则模型的唯一映射。
 *
 * ## 为什么抽出来
 *
 * 回归护栏必须能**把仓库里的 `scripts/rules.json` 喂进生产判据**（而不是在测试里复刻一份映射），
 * 而生产判据要求一个 [NotificationRules]——所以映射必须是可复用的顶层实现。
 *
 * `fallback` 是**惰性**的：云控缺字段时才读 assets/硬编码兜底。
 *
 * C04：随 manager 一起下沉 data 层（它 import 的是 `data.remote.dto` + `domain` 模型，
 * 不再出现 service → data 反向依赖）。
 */
internal object NotificationRulesMapping {

    fun fromDto(dto: NotificationRulesDto, fallback: () -> NotificationRules): NotificationRules {
        // 惰性：云控字段齐全时一次都不会读兜底（读 assets 要解析 JSON，不该白付）
        val defaults: NotificationRules by lazy(LazyThreadSafetyMode.NONE) { fallback() }
        return NotificationRules(
            nls = NlsRules(
                paymentSignalRegex = dto.nls?.paymentSignalRegex ?: defaults.nls.paymentSignalRegex,
                wechat = WechatRules(
                    packageName = dto.nls?.wechat?.packageName ?: defaults.nls.wechat.packageName,
                    directPassTitles = dto.nls?.wechat?.directPassTitles ?: defaults.nls.wechat.directPassTitles,
                    directPassTitleContains = dto.nls?.wechat?.directPassTitleContains ?: defaults.nls.wechat.directPassTitleContains,
                    messagePrefixes = dto.nls?.wechat?.messagePrefixes ?: defaults.nls.wechat.messagePrefixes,
                    amountSymbols = dto.nls?.wechat?.amountSymbols ?: defaults.nls.wechat.amountSymbols,
                ),
                alipay = AlipayRules(
                    packageName = dto.nls?.alipay?.packageName ?: defaults.nls.alipay.packageName,
                    allowedTitleKeywords = dto.nls?.alipay?.allowedTitleKeywords ?: defaults.nls.alipay.allowedTitleKeywords,
                ),
                bankPackagePatterns = (dto.nls?.bankPackagePatterns ?: defaults.nls.bankPackagePatterns)
                    .map(String::trim)
                    .filter(String::isNotBlank),
                smsPackages = (dto.nls?.smsPackages ?: defaults.nls.smsPackages)
                    .map(String::trim)
                    .filter(String::isNotBlank),
                perPackage = dto.nls?.perPackage?.map { p ->
                    PerPackageRule(
                        packageName = p.packageName,
                        packagePattern = p.packagePattern,
                        passAll = p.passAll ?: false,
                        passTitleExact = p.passTitleExact ?: emptyList(),
                        passTitleContains = p.passTitleContains ?: emptyList(),
                        passMsgPrefix = p.passMsgPrefix ?: emptyList(),
                        requireAmountSymbol = p.requireAmountSymbol ?: false,
                        excludeTitleContains = p.excludeTitleContains ?: emptyList(),
                        excludeContentContains = p.excludeContentContains ?: emptyList(),
                    )
                } ?: defaults.nls.perPackage,
                defaultExcludeContent = dto.nls?.defaultRule?.excludeContentContains
                    ?: defaults.nls.defaultExcludeContent,
            ),
            a11y = mapA11y(dto) { defaults },
            sms = SmsRules(
                spamKeywords = dto.smsRules?.spamKeywords ?: defaults.sms.spamKeywords,
            ),
            sourceMapping = dto.sourceMapping ?: defaults.sourceMapping,
            processor = ProcessorRules(
                scoringWindowSeconds = dto.processor?.scoringWindowSeconds ?: defaults.processor.scoringWindowSeconds,
                dedupWindowSeconds = dto.processor?.dedupWindowSeconds ?: defaults.processor.dedupWindowSeconds,
                marketingSuffixCutoffs = dto.processor?.marketingSuffixCutoffs ?: defaults.processor.marketingSuffixCutoffs,
                marketingCommaKeywords = dto.processor?.marketingCommaKeywords ?: defaults.processor.marketingCommaKeywords,
                maxAmountCents = dto.processor?.maxAmountCents ?: defaults.processor.maxAmountCents,
                minAmountCents = dto.processor?.minAmountCents ?: defaults.processor.minAmountCents,
            ),
            // 契约 §3：precheck 不写在 rules.json 里手抄，也不进 assets 兜底。
            precheck = dto.precheck?.toSpec() ?: PrecheckSpec.EMPTY,
        )
    }

    /**
     * `precheck` DTO → 原始领域 spec。固定只认三个 signal 名（`money` / `verb` / `bare_amount`）。
     */
    private fun PrecheckDto.toSpec(): PrecheckSpec {
        val signalSpecs = LinkedHashMap<String, PrecheckSignalSpec>(3)
        signals?.money?.toSpec()?.let { signalSpecs["money"] = it }
        signals?.verb?.toSpec()?.let { signalSpecs["verb"] = it }
        signals?.bareAmount?.toSpec()?.let { signalSpecs["bare_amount"] = it }
        return PrecheckSpec(
            schema = schema ?: 0,
            signals = signalSpecs,
            bareAmountMaxLen = signals?.bareAmountMaxLen ?: PrecheckSpec.DEFAULT_BARE_AMOUNT_MAX_LEN,
            rules = rules.orEmpty().mapNotNull { r ->
                val id = r.id ?: return@mapNotNull null
                PrecheckRuleSpec(
                    id = id,
                    tier = r.tier ?: return@mapNotNull null,
                    type = r.type ?: return@mapNotNull null,
                    pattern = r.pattern,
                    flags = r.flags,
                    expr = r.expr,
                )
            },
        )
    }

    /**
     * `a11y` 子树的映射。`defaults` 传 lambda 而非值：直接传值会强制求值兜底，丢掉惰性。
     */
    private fun mapA11y(dto: NotificationRulesDto, defaults: () -> NotificationRules): A11yRules = A11yRules(
        embeddedPaymentApps = dto.a11y?.embeddedPaymentApps ?: defaults().a11y.embeddedPaymentApps,
        successKeywords = dto.a11y?.successKeywords ?: defaults().a11y.successKeywords,
        embeddedSuccessKeywords = dto.a11y?.embeddedSuccessKeywords ?: defaults().a11y.embeddedSuccessKeywords,
        commonExcludeKeywords = dto.a11y?.commonExcludeKeywords ?: defaults().a11y.commonExcludeKeywords,
        wechatAlipayExcludeKeywords = dto.a11y?.wechatAlipayExcludeKeywords ?: defaults().a11y.wechatAlipayExcludeKeywords,
        amountRegex = dto.a11y?.amountRegex ?: defaults().a11y.amountRegex,
        cooldownMinutes = dto.a11y?.cooldownMinutes ?: defaults().a11y.cooldownMinutes,
        // 云控优先、**硬编码内置**兜底：缺键时直接用 A11yRecognition 的默认，不读资产。
        listPageKeywords = dto.a11y?.listPageKeywords
            ?: A11yRecognition.DEFAULT_LIST_KEYWORDS,
        listPageMinAmounts = dto.a11y?.listPageMinAmounts
            ?: A11yRecognition.LIST_PAGE_MIN_AMOUNTS,
        merchantLabels = dto.a11y?.merchantLabels ?: A11yProfileResolver.DEFAULT_MERCHANT_LABELS,
        profilesSchema = dto.a11y?.profilesSchema,
        appProfiles = dto.a11y?.appProfiles?.mapValues { (_, profile) -> profile.toOverride() }
            ?: emptyMap(),
    )

    /** `a11y.app_profiles.<pkg>` DTO → 领域覆盖值（只是字段搬家，无逻辑）。 */
    private fun A11yAppProfileDto.toOverride(): A11yProfileOverride = A11yProfileOverride(
        successKeywords = successKeywords,
        embeddedSuccessKeywords = embeddedSuccessKeywords,
        excludeKeywords = excludeKeywords,
        amountRegex = amountRegex,
        merchantLabels = merchantLabels,
        listPageHeadChars = listPageHeadChars,
    )

    private fun PrecheckSignalDto.toSpec(): PrecheckSignalSpec =
        PrecheckSignalSpec(pattern = pattern.orEmpty(), flags = flags)
}
