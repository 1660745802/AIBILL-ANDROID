package com.aibill.android.data.notification

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.PerPackageRule
import com.aibill.android.domain.notification.rules.ProcessorRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.WechatRules
import com.aibill.android.domain.notification.PrecheckRuleSpec
import com.aibill.android.domain.notification.PrecheckSignalSpec
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk

/**
 * 预筛单测的公共脚手架。
 *
 * ## 为什么所有判据测试都必须走它
 *
 * 收口前 `NotificationFilterLogicTest` / `NotificationFilterRegressionTest` 把
 * `isLikelyFinancial` / `default_rule` 分支**抄进了测试源码**（注释写着「与
 * NotificationMonitorService 保持一致」）。生产判据改了测试不会红——回归护栏护的是拷贝。
 * 现在两个测试都只提供**规则数据**（词表），判定一律调生产实现。
 */
internal object PrefilterTestHarness {

    const val OWN_PACKAGE = "com.aibill.android"

    /**
     * 「没有重复记录」的 DAO。
     *
     * ⚠️ 不能直接 `mockk(relaxed = true)`：relaxed 模式对**引用类型返回值**会给一个
     * 子 mock（`findDuplicate` 返回 `NotificationRecordEntity?` 也会拿到非 null 的 mock），
     * 于是每条用例都会被 DB 去重拦下，表现为「所有放行断言全红」。
     */
    fun emptyDao(): NotificationRecordDao = mockk<NotificationRecordDao>(relaxed = true).also {
        coEvery { it.findDuplicate(any(), any(), any()) } returns null
    }

    /** 构造一条真实的生产预筛 chain：规则源是 in-memory 版本，零 SP / 零网络。 */
    fun newPrefilter(
        rules: NotificationRules,
        ownPackage: String = OWN_PACKAGE,
        dao: NotificationRecordDao = emptyDao(),
        circuitBreaker: AiCircuitBreaker = mockk(relaxed = true),
        generation: Int = 0,
        stats: PrefilterStats = PrefilterStatsImpl(),
        sampler: CaptureSampler = CaptureSamplerImpl(AppLogger(mockk(relaxed = true)), archiveDao()),
    ): NotificationPrefilter = newPrefilterWithSource(
        source = InMemoryNotificationRuleSource(rules, generation),
        ownPackage = ownPackage,
        dao = dao,
        circuitBreaker = circuitBreaker,
        stats = stats,
        sampler = sampler,
    )

    /** 直接注入任意规则源（用于「云控换代后判据立刻变」这类用例）。 */
    fun newPrefilterWithSource(
        source: NotificationRuleSource,
        ownPackage: String = OWN_PACKAGE,
        dao: NotificationRecordDao = emptyDao(),
        circuitBreaker: AiCircuitBreaker = mockk(relaxed = true),
        stats: PrefilterStats = PrefilterStatsImpl(),
        sampler: CaptureSampler = CaptureSamplerImpl(AppLogger(mockk(relaxed = true)), archiveDao()),
    ): NotificationPrefilter {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns ownPackage
        return NotificationPrefilterImpl(
            context = context,
            ruleSource = source,
            notificationRecordDao = dao,
            appLogger = AppLogger(mockk(relaxed = true)),
            circuitBreaker = circuitBreaker,
            stats = stats,
            sampler = sampler,
        )
    }

    /** 旧硬编码兜底分支的词表（per_package / default_rule 都为空时才会读到）。 */
    data class LegacyFixtures(
        val wechatDirectPassTitles: List<String> = listOf("微信支付", "微信支付凭证"),
        val wechatDirectPassTitleContains: List<String> = listOf("零钱"),
        val wechatMessagePrefixes: List<String> = listOf("[转账]", "[微信红包]"),
        val wechatAmountSymbols: List<String> = listOf("¥", "￥"),
        val alipayAllowedTitleKeywords: List<String> =
            listOf("交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款"),
    )

    /**
     * 组装判据快照的输入。
     *
     * 默认值刻意取**空**（per_package / defaultExcludeContent）：两条分支都不落空时，
     * 生产判据会走到旧硬编码兜底——需要覆盖哪条分支就显式传哪条，不要靠「默认值刚好走到」。
     */
    fun rules(
        paymentSignalRegex: String = FALLBACK_PAYMENT_SIGNAL_PATTERN,
        spamKeywords: List<String> = emptyList(),
        defaultExcludeContent: List<String> = emptyList(),
        perPackage: List<PerPackageRule> = emptyList(),
        bankPackagePatterns: List<String> = listOf("bank", "cmb", "icbc", "ccb", "boc"),
        sourceMapping: Map<String, String> = emptyMap(),
        smsPackages: List<String> = emptyList(),
        legacy: LegacyFixtures = LegacyFixtures(),
        precheck: PrecheckSpec = PrecheckSpec.EMPTY,
        marketingSuffixCutoffs: List<String> = emptyList(),
        marketingCommaKeywords: List<String> = emptyList(),
    ): NotificationRules = NotificationRules(
        nls = NlsRules(
            paymentSignalRegex = paymentSignalRegex,
            wechat = WechatRules(
                packageName = "com.tencent.mm",
                directPassTitles = legacy.wechatDirectPassTitles,
                directPassTitleContains = legacy.wechatDirectPassTitleContains,
                messagePrefixes = legacy.wechatMessagePrefixes,
                amountSymbols = legacy.wechatAmountSymbols,
            ),
            alipay = AlipayRules(
                packageName = "com.eg.android.AlipayGphone",
                allowedTitleKeywords = legacy.alipayAllowedTitleKeywords,
            ),
            bankPackagePatterns = bankPackagePatterns,
            smsPackages = smsPackages,
            perPackage = perPackage,
            defaultExcludeContent = defaultExcludeContent,
        ),
        a11y = A11yRules(
            embeddedPaymentApps = emptyList(),
            successKeywords = listOf("支付成功"),
            embeddedSuccessKeywords = emptyList(),
            commonExcludeKeywords = emptyList(),
            wechatAlipayExcludeKeywords = emptyList(),
            amountRegex = """[¥￥][ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*(\d+\.?\d{0,2})""",
            cooldownMinutes = 5,
        ),
        sms = SmsRules(spamKeywords = spamKeywords),
        sourceMapping = sourceMapping,
        processor = ProcessorRules(10, 60, marketingSuffixCutoffs, marketingCommaKeywords, 10_000_000, 1),
        precheck = precheck,
    )

    /**
     * 在一份判据夹具上叠一层归表（契约 §11.2）。
     *
     * 单独一个入口而不是往 [rules] 再加一个参数：[rules] 已经在 detekt 的参数阈值上，
     * 而归表是**策略**不是**判据**——它叠在已有夹具之上，两者的职责也由此分开。
     */
    fun rulesWithPolicy(
        policy: Map<String, String>,
        base: NotificationRules = rules(),
    ): NotificationRules = base.copy(prefilterPolicy = policy)

    /**
     * 一份接近服务端 `precheck` 载荷的 spec（形状/顺序按跨端契约 §3），供单测注入。
     *
     * `rules[].id` 逐字用契约里的 `precheck.<reason>`；`tier` 对应契约表。
     * 这不是「客户端再抄一份词表」：它是**测试夹具**（客户端运行期只消费云控下发）。
     */
    fun precheckSpec(
        schema: Int = PrecheckSpec.SUPPORTED_SCHEMA,
        bareAmountMaxLen: Int = PrecheckSpec.DEFAULT_BARE_AMOUNT_MAX_LEN,
        moneyFlags: String? = "gi",
        rules: List<PrecheckRuleSpec> = defaultPrecheckRules(),
        extraSignals: Map<String, PrecheckSignalSpec> = emptyMap(),
        /** ⚠️ 缺省 true = 老口径（忽略 channels）；需要验证入口约束时显式传 false。 */
        channelAgnostic: Boolean = true,
        /** 逐条覆盖某个信号（含 channels）。 */
        signalOverrides: Map<String, PrecheckSignalSpec> = emptyMap(),
    ): PrecheckSpec = PrecheckSpec(
        schema = schema,
        signals = linkedMapOf(
            "money" to PrecheckSignalSpec(MONEY_PATTERN, moneyFlags),
            "verb" to PrecheckSignalSpec(VERB_PATTERN, "i"),
            "bare_amount" to PrecheckSignalSpec(BARE_AMOUNT_PATTERN, "i"),
        ).apply {
            putAll(extraSignals)
            putAll(signalOverrides)
        },
        bareAmountMaxLen = bareAmountMaxLen,
        rules = rules,
        channelAgnostic = channelAgnostic,
    )

    /** 契约 §3 的 14 条判据里客户端真正会走到的顺序（简化为可断言的最小集）。 */
    fun defaultPrecheckRules(): List<PrecheckRuleSpec> = listOf(
        PrecheckRuleSpec("precheck.strong_noise", tier = 0, type = "regex", pattern = STRONG_NOISE_PATTERN, flags = "i"),
        PrecheckRuleSpec("precheck.money_verb", tier = 1, type = "predicate", expr = "money && verb"),
        PrecheckRuleSpec("precheck.weak_noise", tier = 0, type = "regex", pattern = WEAK_NOISE_PATTERN, flags = "i"),
        PrecheckRuleSpec("precheck.money_only", tier = 2, type = "predicate", expr = "money"),
        PrecheckRuleSpec("precheck.bare_amount", tier = 2, type = "predicate", expr = "len<=30 && bare_amount"),
        PrecheckRuleSpec("precheck.verb_only", tier = 3, type = "predicate", expr = "verb"),
        PrecheckRuleSpec("precheck.no_signal", tier = 4, type = "fallback"),
    )

    /** 金额信号（锚定货币/数字，避免把纯文字判成金额）。 */
    const val MONEY_PATTERN = "[¥￥][ \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*\\d|\\d+(\\.\\d+)?[ \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*元|rmb[ \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*\\d"

    /** 交易动词。 */
    const val VERB_PATTERN = "支付|付款|消费|扣款|转账|收款|退款|到账|入账|充值|提现|支出|收入"

    /** 光杆数字（「午饭32」）。 */
    const val BARE_AMOUNT_PATTERN = "(^|\\s)\\d+(\\.\\d+)?(\\s|$)"

    /** 强噪声（验证码/广告）。 */
    const val STRONG_NOISE_PATTERN = "验证码|校验码|广告|退订回T"

    /** 弱噪声（仅无正向信号时才拦）。 */
    const val WEAK_NOISE_PATTERN = "为您推荐|优惠活动"
}
