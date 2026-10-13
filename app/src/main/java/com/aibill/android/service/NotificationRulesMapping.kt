package com.aibill.android.service

import com.aibill.android.data.remote.dto.response.A11yAppProfileDto
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.PrecheckDto
import com.aibill.android.data.remote.dto.response.PrecheckSignalDto
import com.aibill.android.domain.notification.A11yProfileOverride
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckSignalSpec
import com.aibill.android.domain.notification.PrecheckSpec

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
            // 契约 §3：precheck **不写在 rules.json 里手抄**，也不进 assets 兜底。
            // 旧服务端无此键 → EMPTY；schema 不认识也由编译期降级（见 PrecheckCompiler）。
            precheck = dto.precheck?.toSpec() ?: PrecheckSpec.EMPTY,
        )
    }

    /**
     * `precheck` DTO → 原始领域 spec。
     *
     * 固定只认三个 signal 名（`money` / `verb` / `bare_amount`）：它们是服务端 `expr`
     * 会引用的键，键名漂移会让整条判据静默失效，所以这里不允许「多一个未知 signal」。
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
     * `a11y` 子树的映射。抽出来只为把 `fromDto` 的圈复杂度压回阈值内（纯搬家，无语义变化）；
     * 另两个新键（`profiles_schema`/`app_profiles`）只在 schema 认识时才被快照层消费。
     *
     * `defaults` 传的是 **lambda 而非值**：直接传值会在调用点强制求值兜底（`by lazy` 的
     * delegate 一旦被当普通参数传递就会被 getValue），把「字段齐全就不读 assets」的惰性丢掉。
     */
    private fun mapA11y(dto: NotificationRulesDto, defaults: () -> NotificationRules): A11yRules = A11yRules(
        embeddedPaymentApps = dto.a11y?.embeddedPaymentApps ?: defaults().a11y.embeddedPaymentApps,
        successKeywords = dto.a11y?.successKeywords ?: defaults().a11y.successKeywords,
        embeddedSuccessKeywords = dto.a11y?.embeddedSuccessKeywords ?: defaults().a11y.embeddedSuccessKeywords,
        commonExcludeKeywords = dto.a11y?.commonExcludeKeywords ?: defaults().a11y.commonExcludeKeywords,
        wechatAlipayExcludeKeywords = dto.a11y?.wechatAlipayExcludeKeywords ?: defaults().a11y.wechatAlipayExcludeKeywords,
        amountRegex = dto.a11y?.amountRegex ?: defaults().a11y.amountRegex,
        cooldownMinutes = dto.a11y?.cooldownMinutes ?: defaults().a11y.cooldownMinutes,
        // 云控优先、**硬编码内置**兜底：缺键时直接用 PaymentAccessibilityRecognition 的默认，
        // 不读资产（资产也可能缺键/读失败），也不在映射层另抄字面量。
        listPageKeywords = dto.a11y?.listPageKeywords
            ?: PaymentAccessibilityRecognition.DEFAULT_LIST_KEYWORDS,
        listPageMinAmounts = dto.a11y?.listPageMinAmounts
            ?: PaymentAccessibilityRecognition.LIST_PAGE_MIN_AMOUNTS,
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
