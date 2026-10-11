package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationRuleSnapshot
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.service.NotificationProcessor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 每源状态闸的行为：内容去重 / 长窗内容记忆 / DB 去重 / AI 熔断。
 *
 * 这些是收口后**从 adapter 搬进 module** 的状态，时间口径统一为
 * `Capture.receivedAt`——所以本文件所有用例都能把时钟钉死，不需要 sleep。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefilterStateGateTest {

    private val rules = PrefilterTestHarness.rules(
        paymentSignalRegex = "元|支付|付款|消费",
        defaultExcludeContent = listOf("优惠券", "秒杀"),
    )

    private fun capture(
        text: String = REAL_TRADE,
        channel: SourceChannel = SourceChannel.NLS,
        packageName: String = "com.example.notifier",
        now: Long = 1_000_000L,
    ) = Capture(
        channel = channel,
        packageName = packageName,
        title = "",
        fullText = text,
        receivedAt = now,
    )

    private fun prefilter(
        dao: NotificationRecordDao = PrefilterTestHarness.emptyDao(),
        processor: NotificationProcessor = mockk(relaxed = true),
    ): NotificationPrefilterImpl = PrefilterTestHarness.newPrefilter(rules, dao = dao, processor = processor)
            as NotificationPrefilterImpl

    private fun reason(verdict: Verdict): BlockReason {
        assertTrue(verdict is Verdict.Block, "期望拦截，实际=$verdict")
        return (verdict as Verdict.Block).reason
    }

    // ── 内存内容去重（5s）──

    @Test
    @DisplayName("同内容 5s 内重复投递被内存去重拦下；过了 5s 不再被去重，但仍不重复调 AI")
    fun `内存内容去重窗口`() = runTest {
        val prefilter = prefilter()
        assertTrue(prefilter.evaluate(capture(now = 1_000L)) is Verdict.Pass)
        assertEquals(BlockReason.DUPLICATE_CONTENT, reason(prefilter.evaluate(capture(now = 2_000L))))
        // 被去重拦下的投递同样会刷新去重键（生产语义），所以这里要跨过 5s 而不是从 1s 起算
        assertEquals(BlockReason.AI_MEMORY, reason(prefilter.evaluate(capture(now = 7_001L))))
        // 两道闸都过了才重新放行（5min 内容记忆过期）
        assertTrue(prefilter.evaluate(capture(now = 301_001L)) is Verdict.Pass)
    }

    @Test
    @DisplayName("不同包名的同内容不算重复")
    fun `内容去重按包名隔离`() = runTest {
        val prefilter = prefilter()
        assertTrue(prefilter.evaluate(capture(packageName = "com.a.app", now = 1_000L)) is Verdict.Pass)
        assertTrue(prefilter.evaluate(capture(packageName = "com.b.app", now = 1_100L)) is Verdict.Pass)
    }

    // ── 长窗内容记忆（5min）：只压重复调 AI，不影响不同内容 ──

    @Test
    @DisplayName("同内容 5 分钟内不再调 AI（长窗记忆），不同内容照常放行")
    fun `长窗内容记忆`() = runTest {
        val prefilter = prefilter()
        assertTrue(prefilter.evaluate(capture(now = 1_000L)) is Verdict.Pass)
        assertEquals(BlockReason.AI_MEMORY, reason(prefilter.evaluate(capture(now = 60_000L))))
        assertEquals(BlockReason.AI_MEMORY, reason(prefilter.evaluate(capture(now = 300_999L))))
        // 记忆过期后重新放行；间隔 5s+ 是因为被拦下的投递同样会刷新 5s 去重键
        assertTrue(prefilter.evaluate(capture(now = 306_000L)) is Verdict.Pass)
        // 不同内容不受长窗影响
        assertTrue(prefilter.evaluate(capture(text = "另一笔 消费12.00元", now = 60_001L)) is Verdict.Pass)
    }

    // ── DB 1s 去重 ──

    @Test
    @DisplayName("DB 1s 去重命中时拦截，同一秒之外的重复不受影响")
    fun `DB去重窗口`() = runTest {
        val dao = mockk<NotificationRecordDao>(relaxed = true)
        coEvery { dao.findDuplicate(any(), any(), any()) } returns
            NotificationRecordEntity(packageName = "com.example.notifier", title = null, content = REAL_TRADE, receivedAt = 1L)
        val prefilter = prefilter(dao = dao)
        assertEquals(BlockReason.DB_DUPLICATE, reason(prefilter.evaluate(capture(now = 1_000L))))
        // 查询窗口本身是 1s：把 DB 变成未命中即可放行（其余闸不拦）
        coEvery { dao.findDuplicate(any(), any(), any()) } returns null
        assertTrue(prefilter.evaluate(capture(text = "另一笔 消费12.00元", now = 1_000L)) is Verdict.Pass)
    }

    // ── 单包 AI 熔断的作用域 ──

    @Test
    @DisplayName("熔断只拦 NLS 的未知来源；已知支付 App 与 SMS/A11Y 不受影响")
    fun `熔断作用域`() = runTest {
        val processor = mockk<NotificationProcessor>(relaxed = true)
        every { processor.isAiMuted(any(), any()) } returns true
        val prefilter = prefilter(processor = processor)

        // NLS + 未知来源 → 拦
        assertEquals(
            BlockReason.AI_MUTED,
            reason(prefilter.evaluate(capture(channel = SourceChannel.NLS, packageName = "com.unknown.app", now = 1_000L))),
        )
        // 已知支付 App（cmb.pb 命中 bankPackagePatterns）→ 不熔断
        assertTrue(
            prefilter.evaluate(capture(packageName = "cmb.pb", text = "招商银行 支出55.00元", now = 1_000L)) is Verdict.Pass
        )
        // SMS / A11Y → 不熔断（401 的 SMS 会入 auth_pending 队列，熔断等于丢交易）
        assertTrue(
            prefilter.evaluate(
                capture(channel = SourceChannel.SMS, packageName = "sms:10695588", text = "支出66.00元", now = 1_000L)
            ) is Verdict.Pass
        )
        assertTrue(
            prefilter.evaluate(
                capture(channel = SourceChannel.A11Y, packageName = "com.unknown.app", text = "支付成功 ¥77.00", now = 1_000L)
            ) is Verdict.Pass
        )
    }

    @Test
    @DisplayName("未熔断时不影响放行")
    fun `未熔断不拦`() = runTest {
        val processor = mockk<NotificationProcessor>(relaxed = true)
        every { processor.isAiMuted(any(), any()) } returns false
        assertTrue(prefilter(processor = processor).evaluate(capture(now = 1_000L)) is Verdict.Pass)
    }

    // ── 规则来源可注入：判据链每次都问注入的源，云控代际变化才能即时生效 ──

    @Test
    @DisplayName("判据链跟随注入的规则源（换源即换判据），且不碰 SP / 网络")
    fun `规则源可注入`() = runTest {
        var rulesNow = PrefilterTestHarness.rules(
            paymentSignalRegex = "不可能命中的词",
            defaultExcludeContent = listOf("秒杀"),
        )
        var snapshotCalls = 0
        val source = object : NotificationRuleSource {
            override fun snapshot(): NotificationRuleSnapshot {
                snapshotCalls++
                return InMemoryNotificationRuleSource(rulesNow).snapshot()
            }
        }
        val prefilter = PrefilterTestHarness.newPrefilterWithSource(source)

        assertEquals(BlockReason.NOT_FINANCIAL, reason(prefilter.evaluate(capture(now = 1_000L))))

        // 云控换代（模拟 ETag 拉到了新词表）后，同一条候选的结论跟着变
        rulesNow = PrefilterTestHarness.rules(
            paymentSignalRegex = "元|支付|付款|消费",
            defaultExcludeContent = listOf("秒杀"),
        )
        assertTrue(prefilter.evaluate(capture(now = 400_000L)) is Verdict.Pass)
        assertEquals(2, snapshotCalls, "判定链必须每次问规则源，代际变化才能即时生效")
    }

    private companion object {
        const val REAL_TRADE = "您尾号1234储蓄卡账户10月28日支出100.00元"
    }
}
