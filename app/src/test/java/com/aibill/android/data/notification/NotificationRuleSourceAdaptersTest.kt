package com.aibill.android.data.notification

import com.aibill.android.service.NotificationRulesManager
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
        assertTrue(snapshot.isKnownSource("com.vendor.newbank.mobile"))
        assertTrue(snapshot.isKnownSource("cmb.pb"), "银行包名模式")
        assertFalse(snapshot.isKnownSource("com.unknown.app"))
        assertTrue(NotificationSourceMapping.KNOWN_PACKAGES.all { it in snapshot.knownPackages })
    }

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
        rules: com.aibill.android.service.NotificationRules,
        generation: Int,
    ): NotificationRulesManager.RulesSnapshot = NotificationRulesManager.RulesSnapshot(rules, generation)
}
