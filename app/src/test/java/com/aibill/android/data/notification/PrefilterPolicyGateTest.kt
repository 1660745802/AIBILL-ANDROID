package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockAction
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 预筛拦截点的**归表查表**（契约附录 C §11.2）。
 *
 * 三态语义（缺省 = `block`）：
 * - `block` → 与今天逐字一致；
 * - `forward` → **不拦，继续走后续判据链**（不是提前放行）；
 * - `sample` → 照旧拦 + 采一条样本（§11.3）。
 *
 * 这里用 `InMemoryNotificationRuleSource` 喂手写归表，钉语义；真载荷的「不丢条目」
 * 在 `PrefilterPolicyTest`。
 */
class PrefilterPolicyGateTest {

    private val t0: Long = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private val stats = PrefilterStatsImpl()
    private val sampler = CaptureSamplerImpl(AppLogger(mockk(relaxed = true)), archiveDao())

    private fun prefilter(policy: Map<String, String>): NotificationPrefilter =
        PrefilterTestHarness.newPrefilter(
            rules = PrefilterTestHarness.rulesWithPolicy(
                policy,
                // 银行包走 legacy 分支 → 账务判据放行，方便把断言聚焦在被测的那一步
                base = PrefilterTestHarness.rules(bankPackagePatterns = listOf("icbc")),
            ),
            stats = stats,
            sampler = sampler,
        )

    private fun capture(
        text: String,
        channel: SourceChannel = SourceChannel.NLS,
        pageText: String? = null,
    ) = Capture(
        channel = channel,
        packageName = "com.icbc.mobilebank",
        title = "工商银行",
        fullText = text,
        receivedAt = t0,
        pageText = pageText,
    )

    /** 日报汇总（第 4 步）——后面还有第 5/6/7/11 步可拦，正好验证「forward 不提前放行」。 */
    private val summaryText = "记账日报 今日共 3 笔 支出"

    @Test
    @DisplayName("缺键 → BLOCK（= 今天的行为），并带上 blockedBy/action 供日志与断言")
    fun `缺键默认 block`() = runTest {
        val verdict = prefilter(emptyMap()).evaluate(capture(summaryText))

        val block = verdict as Verdict.Block
        assertEquals(BlockReason.SUMMARY_REPORT, block.reason)
        assertEquals("client_only.SUMMARY_REPORT", block.blockedBy)
        assertEquals(BlockAction.BLOCK, block.action)
        assertEquals(1L, stats.snapshot().blockedTotal)
        assertTrue(sampler.drain().isEmpty(), "block 不采样")
    }

    @Test
    @DisplayName("forward → 不拦，继续走链；整链都没拦则放行（计入 passed，因为确实去调 AI 了）")
    fun `forward 放行`() = runTest {
        val verdict = prefilter(mapOf("client_only.SUMMARY_REPORT" to "forward")).evaluate(capture(summaryText))

        assertTrue(verdict is Verdict.Pass, "forward 应放行：$verdict")
        val snapshot = stats.snapshot()
        assertEquals(0L, snapshot.blockedTotal, snapshot.toString())
        assertEquals(1L, snapshot.passed)
        assertTrue(sampler.drain().isEmpty())
    }

    @Test
    @DisplayName("forward 不是提前放行：后续判据仍然能拦（日报 forward → 促销句式把它拦下）")
    fun `forward 后仍可被后续判据拦`() = runTest {
        val verdict = prefilter(mapOf("client_only.SUMMARY_REPORT" to "forward"))
            .evaluate(capture("记账日报 今日共 3 笔 花3得29元"))

        val block = verdict as Verdict.Block
        assertEquals(BlockReason.PROMO_SENTENCE, block.reason, "必须继续走完链，而不是在第 4 步就 Pass")
        assertEquals("client_only.PROMO_SENTENCE", block.blockedBy)
    }

    @Test
    @DisplayName("sample → 照旧拦（action=SAMPLE）+ 采一条 prefilter 阶段的样本")
    fun `sample 拦并采样`() = runTest {
        val verdict = prefilter(mapOf("client_only.SUMMARY_REPORT" to "sample")).evaluate(capture(summaryText))

        val block = verdict as Verdict.Block
        assertEquals(BlockAction.SAMPLE, block.action)
        assertEquals(1L, stats.snapshot().blockedTotal)

        val sample = sampler.drain().single()
        assertEquals(SourceChannel.NLS, sample.channel)
        assertEquals(CaptureKind.NOTIFICATION, sample.kind)
        assertEquals(CaptureStage.PREFILTER, sample.stage)
        assertEquals("client_only.SUMMARY_REPORT", sample.blockedBy)
        assertEquals(summaryText, sample.text)
        assertEquals(summaryText.length, sample.textLen)
    }

    @Test
    @DisplayName("A11Y sample → kind=a11y_page 且只采命中片段（text_len 是整页长度）")
    fun `a11y sample 只采片段`() = runTest {
        val page = "账单 ¥12.00 ¥32.50 32.50元 1,000元" + "尾部噪声".repeat(60)
        val verdict = prefilter(mapOf("client_only.LIST_PAGE" to "sample"))
            .evaluate(capture("支付成功 ¥12.00", channel = SourceChannel.A11Y, pageText = page))

        assertEquals(BlockReason.LIST_PAGE, (verdict as Verdict.Block).reason)
        val sample = sampler.drain().single()
        assertEquals(CaptureKind.A11Y_PAGE, sample.kind)
        assertEquals(page.length, sample.textLen, "text_len = 整页长度")
        assertTrue(sample.text!!.length < page.length, "不采整页：${sample.text}")
        assertTrue(sample.text!!.contains("¥12.00"), sample.text!!)
    }

    @Test
    @DisplayName("云控 precheck 也查归表：键用服务端 rule_id，sample 带上 tier")
    fun `precheck 查表`() = runTest {
        val precheckRules = PrefilterTestHarness.rulesWithPolicy(
            mapOf("precheck.strong_noise" to "sample"),
            base = PrefilterTestHarness.rules(
                bankPackagePatterns = listOf("icbc"),
                precheck = PrefilterTestHarness.precheckSpec(),
            ),
        )
        val verdict = PrefilterTestHarness.newPrefilter(rules = precheckRules, stats = stats, sampler = sampler)
            .evaluate(capture("广告 办理贷款"))

        val block = verdict as Verdict.Block
        assertEquals(BlockReason.PRECHECK, block.reason)
        assertEquals("precheck.strong_noise", block.blockedBy, "键就是服务端下发的 rule_id")
        val sample = sampler.drain().single()
        assertEquals("precheck.strong_noise", sample.blockedBy)
        assertEquals(0, sample.tier)
    }

    @Test
    @DisplayName("precheck forward → 放行（转发给服务端预筛判）")
    fun `precheck forward`() = runTest {
        val precheckRules = PrefilterTestHarness.rulesWithPolicy(
            mapOf("precheck.strong_noise" to "forward"),
            base = PrefilterTestHarness.rules(
                bankPackagePatterns = listOf("icbc"),
                precheck = PrefilterTestHarness.precheckSpec(),
            ),
        )
        val verdict = PrefilterTestHarness.newPrefilter(rules = precheckRules, stats = stats, sampler = sampler)
            .evaluate(capture("广告 办理贷款"))

        assertTrue(verdict is Verdict.Pass, "forward 应放行：$verdict")
        assertEquals(1L, stats.snapshot().passed)
    }

    @Test
    @DisplayName("非法值 → BLOCK（写错一个字母的代价是「照旧拦」，不是「静默放行」）")
    fun `非法值按 block`() = runTest {
        val verdict = prefilter(mapOf("client_only.SUMMARY_REPORT" to "Forward"))
            .evaluate(capture(summaryText))

        assertEquals(BlockAction.BLOCK, (verdict as Verdict.Block).action)
        assertEquals(1L, stats.snapshot().blockedTotal)
    }

    @Test
    @DisplayName("归表空/缺键时判据链逐字不变（BlockReason 与计数键都不动）")
    fun `空表行为不变`() = runTest {
        val verdict = prefilter(emptyMap()).evaluate(capture("花3得29元 限时"))
        assertEquals(BlockReason.PROMO_SENTENCE, (verdict as Verdict.Block).reason)
        assertEquals(
            1L,
            stats.snapshot().channels[SourceChannel.NLS]?.blocked?.get("client_only.PROMO_SENTENCE"),
        )
    }

    /** 归表只改「处置」，不改「判据」：同一份输入下被拦的 reason 集合不变。 */
    @Test
    @DisplayName("forward 全开时不再产生任何 Block（但 passed 上升，判据本身没有被改动）")
    fun `全 forward 全部放行`() = runTest {
        val allForward = BlockReason.entries.associate { PrefilterStats.clientOnlyKey(it) to "forward" }
        val allForwardPrefilter = prefilter(allForward)

        for (text in listOf(summaryText, "花3得29元 限时")) {
            val verdict = allForwardPrefilter.evaluate(capture(text))
            assertTrue(verdict is Verdict.Pass, "全 forward 下不应再拦：$text → $verdict")
        }
        val snapshot = stats.snapshot()
        assertEquals(0L, snapshot.blockedTotal, snapshot.toString())
        assertTrue(snapshot.passed >= 2L, snapshot.toString())
    }
}
