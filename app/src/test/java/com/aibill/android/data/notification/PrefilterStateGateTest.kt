package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationRuleSnapshot
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.domain.notification.AiCircuitBreaker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 每源状态闸的行为**与作用域**：内容去重 / 长窗内容记忆 / DB 去重 / AI 熔断。
 *
 * 这些是收口后从 adapter 搬进 module 的状态，时间口径统一为
 * `Capture.receivedAt`——所以本文件所有用例都能把时钟钉死，不需要 sleep。
 *
 * **作用域**：四道闸只在 NLS 开启。它们压的是「同一条内容被系统反复投递」，
 * 而下游明写「同渠道同包名同金额 = 两笔真实交易」——对 SMS/A11Y 闸住同源同文本的
 * 第二条就是丢真交易（两杯咖啡、分钟相同、短信逐字一样）。因此每个用例都按渠道断言：
 * NLS 被拦 / SMS+A11Y 放行。
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
        circuitBreaker: AiCircuitBreaker = mockk(relaxed = true),
    ): NotificationPrefilterImpl = PrefilterTestHarness.newPrefilter(rules, dao = dao, circuitBreaker = circuitBreaker)
            as NotificationPrefilterImpl

    private fun reason(verdict: Verdict): BlockReason {
        assertTrue(verdict is Verdict.Block, "期望拦截，实际=$verdict")
        return (verdict as Verdict.Block).reason
    }

    // ── 内容级状态闸的**作用域**：只在 NLS 生效 ──
    //
    // 这三道闸（5s 内存去重 / 5min 内容记忆 / DB 1s 去重）是为 NLS 的噪音发明的：
    // 资讯 App 反复 post、系统分组摘要单秒 10 条、AI 对同一条标题连续 5001。
    // 而下游明写「同渠道同包名同金额 = 两笔真实交易」，对 SMS/A11Y 闸住同文本的第二条
    // 就是丢真交易，所以它们只在 NLS 开启。

    /**
     * 同内容连投两次：NLS 被内容级闸拦下（[expected] 取决于第二次落在哪个窗口）；
     * SMS/A11Y 两次都放行。
     */
    private suspend fun assertContentGateOnlyAppliesToNls(secondAt: Long, expected: BlockReason) {
        val nls = prefilter()
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertEquals(
            expected,
            reason(nls.evaluate(capture(channel = SourceChannel.NLS, now = secondAt))),
            "NLS 同内容重复投递应被内容级闸拦下",
        )

        for (channel in listOf(SourceChannel.SMS, SourceChannel.A11Y)) {
            val other = prefilter()
            assertTrue(other.evaluate(capture(channel = channel, now = 1_000L)) is Verdict.Pass, "$channel 首次应放行")
            assertTrue(
                other.evaluate(capture(channel = channel, now = secondAt)) is Verdict.Pass,
                "$channel 同源同文本的两条可能是两笔真实交易，预筛不得拦",
            )
        }
    }

    @Test
    @DisplayName("内存内容去重(5s)：只拦 NLS；SMS/A11Y 同文本第二次仍放行")
    fun `内存内容去重窗口`() = runTest {
        assertContentGateOnlyAppliesToNls(secondAt = 2_000L, expected = BlockReason.DUPLICATE_CONTENT)

        // 窗口边界：NLS 过 5s 不再被内存去重拦，但仍在 5min 内容记忆里
        val nls = prefilter()
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertEquals(BlockReason.DUPLICATE_CONTENT, reason(nls.evaluate(capture(channel = SourceChannel.NLS, now = 2_000L))))
        // 被去重拦下的投递同样会刷新去重键（生产语义），所以这里要跨过 5s 而不是从 1s 起算
        assertEquals(BlockReason.AI_MEMORY, reason(nls.evaluate(capture(channel = SourceChannel.NLS, now = 7_001L))))
        // 两道闸都过了才重新放行（5min 内容记忆过期）
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, now = 301_001L)) is Verdict.Pass)
    }

    @Test
    @DisplayName("不同包名的同内容不算重复（仅 NLS 路径）")
    fun `内容去重按包名隔离`() = runTest {
        val nls = prefilter()
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, packageName = "com.a.app", now = 1_000L)) is Verdict.Pass)
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, packageName = "com.b.app", now = 1_100L)) is Verdict.Pass)
    }

    /**
     * 窗口的**闭/开区间**是行为：生产用 `<` 判「未过期」，所以恰好 5s / 5min
     * 应当落到下一道闸（或放行），而不是被当前闸拦下。
     *
     * 变异点：把 `(now - lastSeen) < CONTENT_DEDUP_WINDOW_MS` 改成 `<=`，
     * 或把 `(now - lastAiCall) < AI_CONTENT_MEMORY_MS` 改成 `<=`，本用例都会红。
     */
    @Test
    @DisplayName("窗口边界：距上次恰好 5s / 5min 不算命中")
    fun `窗口边界的闭开区间`() = runTest {
        // 恰好 5s：不被内存去重拦（`< 5000` 不成立），但仍在 5min 内容记忆内
        val fiveSeconds = prefilter()
        assertTrue(fiveSeconds.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertEquals(
            BlockReason.AI_MEMORY,
            reason(fiveSeconds.evaluate(capture(channel = SourceChannel.NLS, now = 6_000L))),
            "距上次恰好 5s 不应命中 5s 去重",
        )

        // 恰好 5min：不被长窗内容记忆拦（`< 300000` 不成立）→ 放行
        val beforeBoundary = prefilter()
        assertTrue(beforeBoundary.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertEquals(
            BlockReason.AI_MEMORY,
            reason(beforeBoundary.evaluate(capture(channel = SourceChannel.NLS, now = 300_999L))),
        )

        // 单开一条链测「恰好 5min 放行」：上一条被拦的投递会刷新 5s 去重键，不能复用它
        val atBoundary = prefilter()
        assertTrue(atBoundary.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertTrue(
            atBoundary.evaluate(capture(channel = SourceChannel.NLS, now = 301_000L)) is Verdict.Pass,
            "距首次恰好 5min 应重新放行",
        )
    }

    @Test
    @DisplayName("自身通知(0) 早于内容去重(1)：同一条自身通知连投两次都是 SELF_NOTIFICATION")
    fun `自身通知早于内容去重`() = runTest {
        val nls = prefilter()
        repeat(2) { i ->
            assertEquals(
                BlockReason.SELF_NOTIFICATION,
                reason(
                    nls.evaluate(
                        capture(
                            channel = SourceChannel.NLS,
                            packageName = PrefilterTestHarness.OWN_PACKAGE,
                            now = 1_000L + i * 1_000L,
                        )
                    )
                ),
                "自身通知应在任何状态闸之前早退（本次第 ${i + 1} 条）",
            )
        }
    }

    @Test
    @DisplayName("长窗内容记忆(5min)：只拦 NLS；不同内容照常放行")
    fun `长窗内容记忆`() = runTest {
        assertContentGateOnlyAppliesToNls(secondAt = 60_000L, expected = BlockReason.AI_MEMORY)

        val nls = prefilter()
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L)) is Verdict.Pass)
        assertEquals(BlockReason.AI_MEMORY, reason(nls.evaluate(capture(channel = SourceChannel.NLS, now = 300_999L))))
        // 记忆过期后重新放行；间隔 5s+ 是因为被拦下的投递同样会刷新 5s 去重键
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, now = 306_000L)) is Verdict.Pass)
        // 不同内容不受长窗影响
        assertTrue(
            nls.evaluate(capture(channel = SourceChannel.NLS, text = "另一笔 消费12.00元", now = 60_001L)) is Verdict.Pass
        )
    }

    // ── 丢账向量：同源同文本的第二条（SMS/A11Y 的真实场景）──

    @Test
    @DisplayName("SMS 同来源同文本的两条都是真实交易：两次都放行（NLS 同文本仍被拦）")
    fun `SMS 同文本两条都放行`() = runTest {
        // 两杯咖啡、分钟相同、银行短信逐字一样 —— 下游按「同渠道同包名同金额」记两笔
        val smsText = "您尾号1234的储蓄卡账户支出15.00元"
        val sms = prefilter()
        assertTrue(
            sms.evaluate(capture(channel = SourceChannel.SMS, packageName = "sms:10695588", text = smsText, now = 1_000L))
                is Verdict.Pass
        )
        assertTrue(
            sms.evaluate(capture(channel = SourceChannel.SMS, packageName = "sms:10695588", text = smsText, now = 30_000L))
                is Verdict.Pass,
            "同源同文本的第二条 SMS 是真实交易，预筛不得拦",
        )

        // 对照：同一条文本从 NLS 进，仍被内存去重拦（噪音压制，不是交易判据）
        val nls = prefilter()
        assertTrue(nls.evaluate(capture(channel = SourceChannel.NLS, text = smsText, now = 1_000L)) is Verdict.Pass)
        assertEquals(
            BlockReason.AI_MEMORY,
            reason(nls.evaluate(capture(channel = SourceChannel.NLS, text = smsText, now = 30_000L))),
        )
    }

    @Test
    @DisplayName("A11Y 同摘要再识别一次仍放行：8s 防抖与金额|商家|页面 冷却归 adapter，预筛不叠层")
    fun `A11Y 同摘要两条都放行`() = runTest {
        val summary = "支付成功 ¥15.00 瑞幸咖啡"
        val a11y = prefilter()
        assertTrue(
            a11y.evaluate(capture(channel = SourceChannel.A11Y, packageName = "com.tencent.mm", text = summary, now = 1_000L))
                is Verdict.Pass
        )
        assertTrue(
            a11y.evaluate(capture(channel = SourceChannel.A11Y, packageName = "com.tencent.mm", text = summary, now = 120_000L))
                is Verdict.Pass,
            "A11Y 的内容级去重归 adapter 的 8s 防抖 + 冷却，预筛不得再拦一道更长的",
        )
    }

    // ── DB 1s 去重 ──

    @Test
    @DisplayName("DB 1s 去重只拦 NLS；SMS/A11Y 即便查到同内容也放行")
    fun `DB去重窗口`() = runTest {
        fun daoWithHit(): NotificationRecordDao = mockk<NotificationRecordDao>(relaxed = true).also {
            coEvery { it.findDuplicate(any(), any(), any()) } returns
                NotificationRecordEntity(
                    packageName = "com.example.notifier",
                    title = null,
                    content = REAL_TRADE,
                    receivedAt = 1L,
                )
        }

        val nlsDao = daoWithHit()
        val nls = prefilter(dao = nlsDao)
        assertEquals(BlockReason.DB_DUPLICATE, reason(nls.evaluate(capture(channel = SourceChannel.NLS, now = 1_000L))))
        // 关联键：查重必须用「包名 + 正文 + 1s 前的截止时刻」，否则 DB 兜底形同虚设
        coVerify(exactly = 1) { nlsDao.findDuplicate("com.example.notifier", REAL_TRADE, 0L) }

        for (channel in listOf(SourceChannel.SMS, SourceChannel.A11Y)) {
            // 每个渠道一个独立 DAO：即便它能查回同内容，也不该被查询（否则无法验证「不查」）
            val dao = daoWithHit()
            val other = prefilter(dao = dao)
            assertTrue(
                other.evaluate(capture(channel = channel, now = 1_000L)) is Verdict.Pass,
                "$channel 不走 DB 内容去重（那是 NLS 的噪音闸）",
            )
            coVerify(exactly = 0) { dao.findDuplicate(any(), any(), any()) }
        }
    }

    // ── 单包 AI 熔断的作用域 ──

    @Test
    @DisplayName("熔断只拦 NLS 的未知来源；已知支付 App 与 SMS/A11Y 不受影响")
    fun `熔断作用域`() = runTest {
        val circuitBreaker = mockk<AiCircuitBreaker>(relaxed = true)
        every { circuitBreaker.isMuted(any(), any()) } returns true
        val prefilter = prefilter(circuitBreaker = circuitBreaker)

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
        val circuitBreaker = mockk<AiCircuitBreaker>(relaxed = true)
        every { circuitBreaker.isMuted(any(), any()) } returns false
        assertTrue(prefilter(circuitBreaker = circuitBreaker).evaluate(capture(now = 1_000L)) is Verdict.Pass)
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
