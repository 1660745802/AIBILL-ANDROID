package com.aibill.android.data.notification

import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.NlsRulesDto
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.data.notification.rules.NotificationRulesManager
import com.aibill.android.data.notification.rules.NotificationRulesMapping
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationSourceMapping
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 判据数据源的两个 adapter。
 *
 * 断言的是**契约**而不是实现细节：云控源在代际变化时重新派生、in-memory 源永不变、
 * 非法 `payment_signal_regex` 回退到与历史常量逐字相同的兜底。
 */
class NotificationRuleSourceAdaptersTest {

    private val rules = PrefilterTestHarness.rules(
        paymentSignalRegex = "元|支付",
        spamKeywords = listOf("恭喜", "领取"),
        defaultExcludeContent = listOf("优惠券", "秒杀"),
        sourceMapping = mapOf("com.vendor.newbank.mobile" to "新银行"),
        smsPackages = listOf("com.android.mms"),
        bankPackagePatterns = listOf("bank", "cmb"),
    )

    // ── in-memory adapter ──

    @Test
    @DisplayName("in-memory 源：同一实例多次取快照是同一个对象（零 IO、零重派生）")
    fun `in-memory 源稳定`() {
        val source = InMemoryNotificationRuleSource(rules, generation = 3)
        assertSame(source.snapshot(), source.snapshot())
        assertEquals(3, source.snapshot().generation)
    }

    @Test
    @DisplayName("in-memory 源派生出完整判据快照")
    fun `in-memory 源派生快照`() {
        val snapshot = InMemoryNotificationRuleSource(rules).snapshot()
        assertTrue(snapshot.paymentSignal.containsMatchIn("支出10.00元"))
        assertTrue(snapshot.isMarketing("恭喜您获得优惠券"))
        // 强交易特征豁免：命中营销词但有「尾号」时不算营销
        assertFalse(snapshot.isMarketing("尾号1234支出10.00元，点击领取"))
        assertTrue(snapshot.isKnownSource("com.vendor.newbank.mobile"), "云控 source_mapping")
        assertTrue(snapshot.isKnownSource("com.android.mms"), "云控 sms_packages")
        assertTrue(snapshot.isKnownSource("cmb.pb"), "银行包名模式")
        assertFalse(snapshot.isKnownSource("com.unknown.app"))
        assertTrue(NotificationSourceMapping.KNOWN_PACKAGES.all { it in snapshot.knownPackages })
    }

    /**
     * 「已知来源」判据的回归护栏（收口后它的唯一实现）。
     *
     * 原先这条断言打在 `NotificationRulesManager.isKnownOrBankPackage` 上，
     * 与 `NotificationRuleSnapshot.isKnownSource` 是同一套判据的第二个入口，
     * 已连同那个入口一起删除；语义（四路来源 + 非已知）原样保留在这里。
     */
    @Test
    @DisplayName("云控规则直接进入已知来源判据：source_mapping / sms_packages / 银行模式 / 非已知")
    fun `云控规则驱动已知来源判定`() {
        val snapshot = InMemoryNotificationRuleSource(cloudRules()).snapshot()
        assertTrue(snapshot.isKnownSource("com.example.wallet"), "云控 source_mapping")
        assertTrue(snapshot.isKnownSource("com.example.sms"), "云控 sms_packages")
        assertTrue(snapshot.isKnownSource("com.vendor.newbank.mobile"), "银行包名模式（包含匹配）")
        assertFalse(snapshot.isKnownSource("com.example.unrelated"))
    }

    @Test
    @DisplayName("空银行模式不会匹配所有包名")
    fun `空银行模式不匹配所有包名`() {
        val rules = PrefilterTestHarness.rules(bankPackagePatterns = listOf(" ", ""))
        assertFalse(InMemoryNotificationRuleSource(rules).snapshot().isKnownSource("com.example.unrelated"))
    }

    /**
     * 营销词表是两份云控词表的**并集**：sms.spam_keywords 与 nls.default_rule.exclude_content_contains。
     *
     * 变异点：把 deriveSnapshot 里的 `spamKeywords + defaultExcludeContent` 改成只取其中一份，
     * 本用例会红（另一份词表静默失效，营销推送会穿透到 AI）。
     */
    @Test
    @DisplayName("营销词表 = sms.spam_keywords ∪ default_rule.exclude_content_contains")
    fun `营销词表是两份词表的并集`() {
        val snapshot = InMemoryNotificationRuleSource(
            PrefilterTestHarness.rules(
                spamKeywords = listOf("恭喜"),
                defaultExcludeContent = listOf("秒杀"),
            )
        ).snapshot()
        assertTrue(snapshot.isMarketing("恭喜您获得奖励"), "sms.spam_keywords 侧应生效")
        assertTrue(snapshot.isMarketing("限时秒杀"), "default_rule.exclude_content_contains 侧应生效")
    }

    /** 走生产映射把云控 DTO 变成规则模型（同 `NotificationRulesMapping`，不重抄一份）。 */
    private fun cloudRules(): NotificationRules = NotificationRulesMapping.fromDto(
        NotificationRulesDto(
            nls = NlsRulesDto(
                paymentSignalRegex = "¥|支付",
                wechat = null,
                alipay = null,
                bankPackagePatterns = listOf("newbank"),
                smsPackages = listOf("com.example.sms"),
                perPackage = null,
                defaultRule = null,
            ),
            a11y = null,
            smsRules = null,
            sourceMapping = mapOf("com.example.wallet" to "示例钱包"),
            processor = null,
        )
        // 云控只下发 nls + source_mapping，其余段缺省走兜底（生产同一条映射）
    ) { PrefilterTestHarness.rules() }

    @Test
    @DisplayName("非法 payment_signal_regex 回退到硬编码兜底，且与历史常量逐字相同")
    fun `非法正则回退兜底`() {
        val broken = PrefilterTestHarness.rules(paymentSignalRegex = "[未闭合")
        val snapshot = InMemoryNotificationRuleSource(broken).snapshot()
        assertTrue(snapshot.paymentSignal.containsMatchIn("支出人民币20.00"))
        assertEquals(FALLBACK_PAYMENT_SIGNAL_PATTERN, snapshot.paymentSignal.pattern)
    }

    // ── cloud adapter ──

    @Test
    @DisplayName("cloud 源：代际不变时复用快照，代际变化时重新派生")
    fun `cloud 源按代际重派生`() {
        val manager = mockk<NotificationRulesManager>()
        val initial = manager.rulesSnapshot(rules, generation = 1)
        val updated = manager.rulesSnapshot(
            PrefilterTestHarness.rules(paymentSignalRegex = "只认这个信号", defaultExcludeContent = listOf("秒杀")),
            generation = 2,
        )
        var current = initial
        every { manager.getSnapshot() } answers { current }

        val source = CloudNotificationRuleSource(manager, AppLogger(mockk(relaxed = true)))
        val first = source.snapshot()
        assertSame(first, source.snapshot(), "代际没变就不该重新派生")
        assertTrue(first.paymentSignal.containsMatchIn("支出10.00元"))

        current = updated
        val second = source.snapshot()
        assertNotSame(first, second)
        assertEquals(2, second.generation)
        assertFalse(second.paymentSignal.containsMatchIn("支出10.00元"), "新代际的词表应当生效")
    }

    private fun NotificationRulesManager.rulesSnapshot(
        rules: com.aibill.android.domain.notification.rules.NotificationRules,
        generation: Int,
    ): NotificationRulesManager.RulesSnapshot = NotificationRulesManager.RulesSnapshot(rules, generation)
}
