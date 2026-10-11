package com.aibill.android.service

import com.aibill.android.data.remote.dto.response.NotificationRulesDto

/**
 * 云控 DTO → 规则模型的唯一映射。
 *
 * ## 为什么抽出来
 *
 * 收口前它是 [NotificationRulesManager] 的私有方法。收口后回归护栏必须能
 * **把仓库里的 `scripts/rules.json` 喂进生产判据**（而不是在测试里复刻一份映射），
 * 而生产判据要求一个 [NotificationRules]——所以映射必须是可复用的顶层实现。
 *
 * `fallback` 是**惰性**的：云控缺字段时才读 assets/硬编码兜底，
 * 与迁移前的行为一致（正常启动不会因为云控字段齐全而多读一次 assets）。
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
            a11y = A11yRules(
                embeddedPaymentApps = dto.a11y?.embeddedPaymentApps ?: defaults.a11y.embeddedPaymentApps,
                successKeywords = dto.a11y?.successKeywords ?: defaults.a11y.successKeywords,
                embeddedSuccessKeywords = dto.a11y?.embeddedSuccessKeywords ?: defaults.a11y.embeddedSuccessKeywords,
                commonExcludeKeywords = dto.a11y?.commonExcludeKeywords ?: defaults.a11y.commonExcludeKeywords,
                wechatAlipayExcludeKeywords = dto.a11y?.wechatAlipayExcludeKeywords ?: defaults.a11y.wechatAlipayExcludeKeywords,
                amountRegex = dto.a11y?.amountRegex ?: defaults.a11y.amountRegex,
                cooldownMinutes = dto.a11y?.cooldownMinutes ?: defaults.a11y.cooldownMinutes,
            ),
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
        )
    }
}
