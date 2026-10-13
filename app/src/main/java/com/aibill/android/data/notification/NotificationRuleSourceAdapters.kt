package com.aibill.android.data.notification

import com.aibill.android.domain.notification.A11yGlobalProfile
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.LegacyCriteria
import com.aibill.android.domain.notification.NotificationRuleSnapshot
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.PerPackageCriteria
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.PerPackageRule
import com.aibill.android.data.notification.rules.NotificationRulesManager
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationSourceMapping
import timber.log.Timber

/**
 * 判据数据源的两个 adapter 共用的派生逻辑。
 *
 * 两者的区别只有「规则从哪来、要不要看代际」：
 * - [CloudNotificationRuleSource] 每次调用比对云控代际，变则重新派生；
 * - [InMemoryNotificationRuleSource] 构造时派生一次，永远不变。
 *
 * 派生本身（编译正则、展开已知包名、映射 per_package）只写一遍，
 * 避免单测用一份「看起来一样」的映射、实际与生产漂移。
 */
internal fun deriveSnapshot(rules: NotificationRules, generation: Int, logger: AppLogger?): NotificationRuleSnapshot {
    val paymentSignal = compilePaymentSignal(rules.nls.paymentSignalRegex, logger)
    val knownPackages = LinkedHashSet<String>().apply {
        addAll(NotificationSourceMapping.KNOWN_PACKAGES)
        addAll(rules.sourceMapping.keys)
        addAll(rules.nls.smsPackages)
    }
    logger?.info(
        "NLS",
        "规则已加载 app=${com.aibill.android.BuildConfig.VERSION_NAME}" +
            "(${com.aibill.android.BuildConfig.VERSION_CODE}) gen=$generation " +
            // 字段与迁移前的 NLS.loadRules() 保持一致（运维日志按这些 key 排查）
            "wechatDirectPassTitles=${rules.nls.wechat.directPassTitles.size} " +
            "perPackage=${rules.nls.perPackage.size} " +
            "bankPatterns=${rules.nls.bankPackagePatterns.size} " +
            "spamKeywords=${rules.sms.spamKeywords.size} " +
            "knownPackages=$knownPackages.size"
    )
    // 全局 A11Y 摘取配置：amount_regex 在这里**只编译一次**（失败→内置兜底）；
    // profile 的逐键回退与告警也在这里一次做完，服务与预筛共用同一份结果。
    val a11yGlobalProfile = A11yGlobalProfile(
        successKeywords = rules.a11y.successKeywords,
        embeddedSuccessKeywords = rules.a11y.embeddedSuccessKeywords,
        commonExcludeKeywords = rules.a11y.commonExcludeKeywords,
        wechatAlipayExcludeKeywords = rules.a11y.wechatAlipayExcludeKeywords,
        amountRegex = runCatching { Regex(rules.a11y.amountRegex) }.getOrElse {
            Timber.w("预筛: invalid a11y.amountRegex from rules, using hardcoded fallback")
            logger?.warn("A11Y", "全局 amount_regex 非法，回退内置兜底")
            A11yProfileResolver.FALLBACK_AMOUNT_REGEX
        },
        merchantLabels = rules.a11y.merchantLabels,
    )
    val a11yPackageProfiles = A11yProfileResolver.resolveAll(
        profilesSchema = rules.a11y.profilesSchema,
        overrides = rules.a11y.appProfiles,
        embeddedPaymentApps = rules.a11y.embeddedPaymentApps
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet(),
        global = a11yGlobalProfile,
    ) { pkg, key, reason ->
        logger?.warn("A11Y", "A11Y profile 回退: pkg=$pkg key=$key reason=$reason")
    }
    return NotificationRuleSnapshot(
        generation = generation,
        paymentSignal = paymentSignal,
        marketingWords = rules.sms.spamKeywords + rules.nls.defaultExcludeContent,
        defaultExcludeContent = rules.nls.defaultExcludeContent,
        knownPackages = knownPackages,
        bankPackagePatterns = rules.nls.bankPackagePatterns,
        perPackage = rules.nls.perPackage.map(PerPackageRule::toCriteria),
        legacy = LegacyCriteria(
            wechatDirectPassTitles = rules.nls.wechat.directPassTitles,
            wechatDirectPassTitleContains = rules.nls.wechat.directPassTitleContains,
            wechatMessagePrefixes = rules.nls.wechat.messagePrefixes,
            wechatAmountSymbols = rules.nls.wechat.amountSymbols,
            alipayAllowedTitleKeywords = rules.nls.alipay.allowedTitleKeywords,
        ),
        // C04 决策 2：这些字段原先由 4 处 `getRules()` 绕过快照直接读，现随代际进快照。
        scoringWindowSeconds = rules.processor.scoringWindowSeconds,
        dedupWindowSeconds = rules.processor.dedupWindowSeconds,
        maxAmountCents = rules.processor.maxAmountCents,
        minAmountCents = rules.processor.minAmountCents,
        sourceMapping = rules.sourceMapping,
        // precheck 与其它判据同一代际：编译一次、随代际更新（全或无的容错语义在 PrecheckCompiler）。
        precheck = PrecheckCompiler.compile(rules.precheck, logger),
        // 营销后缀截断词随代际进快照：预筛要用它算出与请求 `input` 一致的串。
        marketingSuffixCutoffs = rules.processor.marketingSuffixCutoffs,
        marketingCommaKeywords = rules.processor.marketingCommaKeywords,
        // A11Y 列表页启发式参数：云控优先、内置兜底（预筛对空/非正值再兜底）。
        a11yListPageKeywords = rules.a11y.listPageKeywords,
        a11yListPageMinAmounts = rules.a11y.listPageMinAmounts,
        a11yGlobalProfile = a11yGlobalProfile,
        a11yPackageProfiles = a11yPackageProfiles,
    )
}

/**
 * 云控给的 `payment_signal_regex` 编译失败时回退到硬编码兜底。
 *
 * 兜底正则与原 `NotificationMonitorService.PAYMENT_SIGNAL` **逐字相同**：
 * 它是「规则缺字段/写坏」时的向后兼容下限，不是新判据。
 */
private fun compilePaymentSignal(pattern: String, logger: AppLogger?): Regex = try {
    Regex(pattern)
} catch (e: Exception) {
    Timber.w(e, "预筛: invalid paymentSignalRegex from rules, using hardcoded fallback")
    logger?.warn("NLS", "payment_signal_regex 非法，已回退硬编码兜底")
    Regex(FALLBACK_PAYMENT_SIGNAL_PATTERN)
}

/**
 * 支付特征关键词（向后兼容兜底）。
 *
 * ⚠️ 这是历史常量，不是「推荐词表」：云控 `nls.payment_signal_regex` 才是真正生效的。
 * 它只在云控缺该字段或正则写坏时生效；收口前它叫
 * `NotificationMonitorService.PAYMENT_SIGNAL`（SMS 侧通过它引用，而不是自己留一份）。
 */
internal const val FALLBACK_PAYMENT_SIGNAL_PATTERN = "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|" +
    "转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|" +
    "余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"


/** 云控 DTO → 领域判据。映射本身零逻辑，只是把「云控字段名」翻译成「判据字段名」。 */
private fun PerPackageRule.toCriteria() = PerPackageCriteria(
    packageName = packageName,
    packagePattern = packagePattern,
    passAll = passAll,
    passTitleExact = passTitleExact,
    passTitleContains = passTitleContains,
    passMsgPrefix = passMsgPrefix,
    requireAmountSymbol = requireAmountSymbol,
    excludeTitleContains = excludeTitleContains,
    excludeContentContains = excludeContentContains,
)

/**
 * 生产 adapter：云控 + ETag + 代际缓存。
 *
 * 云控侧的所有事（SharedPreferences 落盘、ETag、304、代际自增）都由
 * [NotificationRulesManager] 负责，本类只做「代际变了就重新派生一次」。
 * 判据链因此可以在任意线程无锁读取快照。
 */
class CloudNotificationRuleSource(
    private val rulesManager: NotificationRulesManager,
    private val logger: AppLogger,
) : NotificationRuleSource {

    @Volatile
    private var cached: NotificationRuleSnapshot? = null

    override fun snapshot(): NotificationRuleSnapshot {
        val managerSnapshot = rulesManager.getSnapshot()
        cached?.let { if (it.generation == managerSnapshot.generation) return it }
        return synchronized(this) {
            val current = cached
            if (current != null && current.generation == managerSnapshot.generation) {
                current
            } else {
                deriveSnapshot(managerSnapshot.rules, managerSnapshot.generation, logger).also {
                    cached = it
                    Timber.d("预筛: 规则快照已派生 generation=${it.generation}")
                }
            }
        }
    }
}

/**
 * 单测 adapter：固定规则、零 IO。
 *
 * 存在的意义是让整条判定链可以在纯 JVM 单测里跑——
 * 生产 adapter 背后是 SharedPreferences + Retrofit + assets，
 * 而「判据链不碰 SharedPreferences / 网络」这条本身就是回归护栏之一。
 *
 * [generation] 可显式指定，便于断言「代际变化时快照随之变化」。
 */
class InMemoryNotificationRuleSource(
    private val rules: NotificationRules,
    private val generation: Int = 0,
) : NotificationRuleSource {

    private val snapshot: NotificationRuleSnapshot by lazy { deriveSnapshot(rules, generation, logger = null) }

    override fun snapshot(): NotificationRuleSnapshot = snapshot
}
