package com.aibill.android.data.notification

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.service.A11yRules
import com.aibill.android.service.AlipayRules
import com.aibill.android.service.NlsRules
import com.aibill.android.service.NotificationRules
import com.aibill.android.service.PerPackageRule
import com.aibill.android.service.ProcessorRules
import com.aibill.android.service.SmsRules
import com.aibill.android.service.WechatRules
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
    ): NotificationPrefilter = newPrefilterWithSource(
        source = InMemoryNotificationRuleSource(rules, generation),
        ownPackage = ownPackage,
        dao = dao,
        circuitBreaker = circuitBreaker,
    )

    /** 直接注入任意规则源（用于「云控换代后判据立刻变」这类用例）。 */
    fun newPrefilterWithSource(
        source: NotificationRuleSource,
        ownPackage: String = OWN_PACKAGE,
        dao: NotificationRecordDao = emptyDao(),
        circuitBreaker: AiCircuitBreaker = mockk(relaxed = true),
        stats: PrefilterStats = PrefilterStatsImpl(),
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
            amountRegex = """[¥￥]\s*(\d+\.?\d{0,2})""",
            cooldownMinutes = 5,
        ),
        sms = SmsRules(spamKeywords = spamKeywords),
        sourceMapping = sourceMapping,
        processor = ProcessorRules(10, 60, emptyList(), emptyList(), 10_000_000, 1),
    )
}
