package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.rules.ProcessorRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.WechatRules
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [ScoredAdmission] 的接口级测试：**虚拟时间**下直接驱动 [ScoredAdmission.submit]，
 * 以产出回调观察窗口边界与判重结果。
 *
 * 覆盖：10s 窗口的到期、跨渠道择优（含窗口锚点不后移）、短时同额合并为一笔、
 * 60s 跨渠道判重（内存 + DB 兜底）、以及「写库在锁外」——一条产出卡在网络上时，
 * 另一笔的产出不被阻塞。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoredAdmissionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dao: NotificationRecordDao = mockk(relaxed = true)
    private lateinit var ruleSource: NotificationRuleSource
    private lateinit var admission: ScoredAdmissionImpl

    /** 与生产同一个时间锚点的基准时刻；已认领记录以 receivedAt 为时间轴，测试用它对齐。 */
    private val baseTime = System.currentTimeMillis()

    @BeforeEach
    fun setUp() {
        ruleSource = InMemoryNotificationRuleSource(rules(scoringSeconds = 10, dedupSeconds = 60))
        coEvery { dao.findRecentSameAmount(any(), any(), any()) } returns null
        admission = ScoredAdmissionImpl(dao, ruleSource, AppLogger(mockk(relaxed = true)))
        admission.testDispatcher = testDispatcher
    }

    private fun rules(scoringSeconds: Int, dedupSeconds: Int) = NotificationRules(
        nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
        a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
        sms = SmsRules(emptyList()),
        sourceMapping = emptyMap(),
        processor = ProcessorRules(scoringSeconds, dedupSeconds, emptyList(), emptyList(), 10_000_000, 1),
    )

    private fun candidate(
        amount: Int,
        score: Int,
        channel: SourceChannel = SourceChannel.NLS,
        packageName: String = "com.tencent.mm",
        receivedAt: Long = baseTime,
        isComplete: Boolean = true,
        description: String? = null,
    ) = ParsedTransaction(
        capture = Capture(channel, packageName, "标题", "支付成功 ¥${amount / 100}", receivedAt),
        amount = amount,
        type = "expense",
        categoryId = 7,
        categoryName = "餐饮",
        categoryIcon = "🍜",
        description = description,
        score = score,
        isComplete = isComplete,
    )

    // ── 评分窗口 ──

    @Test
    @DisplayName("候选在 10s 评分窗口到期前不产出，到期后产出一次")
    fun `切换账号清除旧窗口与已认领金额`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(3200, score = 5)) { admitted += it }
        admission.reset()
        admission.submit(candidate(3200, score = 5, description = "新账号")) { admitted += it }
        advanceUntilIdle()
        assertEquals(listOf("新账号"), admitted.map { it.description })
        admission.reset()
        admission.submit(candidate(3200, score = 5, description = "另一个账号")) { admitted += it }
        advanceUntilIdle()
        assertEquals(2, admitted.size)
    }

    @Test
    fun `窗口未到期不产出，到期产出`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(3200, score = 5)) { admitted += it }
        runCurrent()

        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(admitted.isEmpty(), "窗口未到期不应产出")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, admitted.size, "窗口到期应产出一次")
        assertEquals(3200, admitted.single().amount)
    }

    @Test
    @DisplayName("同金额跨渠道 10s 内 → 只产出 score 最高的那个")
    fun `跨渠道同金额取高分`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(2500, score = 2, channel = SourceChannel.NLS)) { admitted += it }
        runCurrent()

        advanceTimeBy(3_000)
        runCurrent()
        admission.submit(
            candidate(2500, score = 5, channel = SourceChannel.A11Y, packageName = "com.eg.android.AlipayGphone")
        ) { admitted += it }
        runCurrent()

        advanceTimeBy(12_000)
        runCurrent()

        assertEquals(1, admitted.size, "同一笔的跨渠道视图只应入一笔")
        assertEquals(SourceChannel.A11Y, admitted.single().capture.channel, "应保留信息更全的那个视图")
    }

    @Test
    @DisplayName("低分候选不替换，且窗口锚点仍是先到那条（不后移）")
    fun `低分不替换且窗口锚点不后移`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(2500, score = 5, channel = SourceChannel.A11Y, description = "更全")) { admitted += it }
        runCurrent()

        // 9s 后才来一个低分视图：不替换，但窗口到期时刻仍锚在第一条（10s）
        advanceTimeBy(9_000)
        runCurrent()
        admission.submit(candidate(2500, score = 2, channel = SourceChannel.NLS, receivedAt = baseTime + 9_000)) { admitted += it }
        runCurrent()

        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(1, admitted.size, "窗口应在首条到达后 10s 到期，而不是被后到那条重新计时")
        assertEquals("更全", admitted.single().description)
    }

    /**
     * 评分窗口是**闭区间**：`receivedAt` 差恰好 10s 仍算同一笔。
     *
     * 变异点：把 `(candidate.receivedAt - existing.receivedAt) <= scoreWindowMs`
     * 改成 `<`，后到的跨渠道视图会另开窗口并 cancel 首条的定时任务，本用例变红。
     */
    @Test
    @DisplayName("评分窗口闭区间：receivedAt 差恰好 10s 仍算同一笔，不另开窗口")
    fun `评分窗口恰好10s边界`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(2500, score = 5, channel = SourceChannel.NLS)) { admitted += it }
        runCurrent()

        // 时钟推到窗口边界，但不 runCurrent：此刻定时任务尚未执行，窗口仍在池中
        advanceTimeBy(10_000)
        admission.submit(
            candidate(
                2500,
                score = 2,
                channel = SourceChannel.A11Y,
                packageName = "com.eg.android.AlipayGphone",
                receivedAt = baseTime + 10_000,
            )
        ) { admitted += it }
        runCurrent()

        assertEquals(1, admitted.size, "恰好 10s 属于同一评分窗口，不应被拆成两个窗口")
        assertEquals(
            SourceChannel.NLS,
            admitted.single().capture.channel,
            "同一窗口内低分不替换，仍产出首条",
        )
    }

    @Test
    @DisplayName("短时同额 = 一笔：同渠道同包名同金额 10s 内不再拆成两笔")
    fun `同来源同金额合并为一笔`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(1500, score = 5, description = "咖啡1")) { admitted += it }
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        // 同渠道、同包名、同金额 —— 改动前会被当成「两笔真实交易」立即提交前一条。
        // 现在与跳渠道一样：择优保留，窗口到期只产出一笔。
        admission.submit(candidate(1500, score = 5, receivedAt = baseTime + 2_000, description = "咖啡2")) { admitted += it }
        runCurrent()

        assertTrue(admitted.isEmpty(), "窗口未到期前不得产出（改动前会立即产出一份）")

        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(1, admitted.size, "同额两条在窗口内只产出一笔")
        assertEquals("咖啡1", admitted.single().description, "分数打平时保留先到的那条")
    }

    @Test
    @DisplayName("短时同额：分数高的胜出（无论是否同来源）")
    fun `同额择优保留高分`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(1500, score = 3, description = "低分")) { admitted += it }
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        admission.submit(
            candidate(1500, score = 7, receivedAt = baseTime + 2_000, description = "高分")
        ) { admitted += it }
        runCurrent()

        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(1, admitted.size)
        assertEquals("高分", admitted.single().description, "高分候选应替换窗口内那条")
    }

    @Test
    @DisplayName("回归·线上现场：同包名同额相隔 33s（超评分窗口）只入一笔")
    fun `同包名超窗同额只入一笔`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        // 2026-10-16 ¥65.06 实录：Alipay 同一笔消费的两个通知视图
        //   08:19:28 支付成功 ￥65.06 … 回首页 家家悦回龙观店 完成
        //   08:20:00 支付成功 ￥65.06 … 65.06 家家悦回龙观店 付款方式
        // 相隔 33s > 评分窗口 10s → 走不到窗口合并；但它们在 60s 去重窗内，
        // 且**同包名** —— 改动前这一条被 `package_name !=` 放过去，两条账都入了。
        val alipay = "com.eg.android.AlipayGphone"

        admission.submit(candidate(6506, score = 5, packageName = alipay)) { admitted += it }
        runCurrent()
        advanceTimeBy(10_100)
        runCurrent()   // 第一笔已走完窗口并产出
        assertEquals(1, admitted.size, "第一笔应正常入账")

        admission.submit(
            candidate(6506, score = 5, packageName = alipay, receivedAt = baseTime + 33_000)
        ) { admitted += it }
        runCurrent()
        advanceTimeBy(10_100)
        runCurrent()

        assertEquals(
            1,
            admitted.size,
            "同包名相隔 33s 的同额必须被 60s 去重层拦下（否则同一笔又出两条账）",
        )
    }

    @Test
    @DisplayName("401 重放的历史 receivedAt 不被近期跨渠道条目误判为重复")
    fun `重放历史时间不误杀`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        // 实时通知：NLS ¥50 已入账（记入 recentProcessed，时间轴 = receivedAt）
        admission.submit(candidate(5000, score = 5)) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(1, admitted.size)

        // 1 小时前的 auth_pending 待补偿重放：A11Y 同金额，receivedAt 是历史时刻。
        // 单边时间差为负，不能被当成「60s 内」而把重放交易当重复丢掉。
        admission.submit(
            candidate(
                5000,
                score = 5,
                channel = SourceChannel.A11Y,
                packageName = "com.eg.android.AlipayGphone",
                receivedAt = baseTime - 3_600_000,
            )
        ) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()

        assertEquals(2, admitted.size, "历史时刻的另一笔同金额交易不应被近期条目误判为重复")
    }

    // ── 60s 跨渠道判重 ──

    @Test
    @DisplayName("60s 内同金额跨渠道 → 第二条不产出")
    fun `跨渠道60s判重`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(8800, score = 5)) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(1, admitted.size)

        // 30s 后 A11Y 从另一渠道报出同一笔（同金额）
        advanceTimeBy(20_000)
        runCurrent()
        admission.submit(
            candidate(8800, score = 5, channel = SourceChannel.A11Y, packageName = "com.eg.android.AlipayGphone", receivedAt = baseTime + 30_000)
        ) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()

        assertEquals(1, admitted.size, "跨渠道同一笔只应入一笔")
        // 关联键：DB 兜底查重必须用「金额 + receivedAt-60s + 包名」，用 channel 或 wall clock 都错
        coVerify(exactly = 1) {
            dao.findRecentSameAmount(8800, baseTime - 60_000, baseTime + 60_000)
        }
    }

    @Test
    @DisplayName("60s 之外的同金额是另一笔 → 正常产出")
    fun `超过60s不算重复`() = runTest(testDispatcher) {
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(8800, score = 5)) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()

        admission.submit(
            candidate(8800, score = 5, channel = SourceChannel.A11Y, packageName = "com.eg.android.AlipayGphone", receivedAt = baseTime + 61_000)
        ) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()

        assertEquals(2, admitted.size)
    }

    @Test
    @DisplayName("内存判重漏网时 DB 兜底仍拦住跨渠道重复")
    fun `DB兜底判重`() = runTest(testDispatcher) {
        coEvery { dao.findRecentSameAmount(any(), any(), any()) } returns
            NotificationRecordEntity(
                id = 1L,
                packageName = "cmb.pb",
                content = "扣款3.00",
                parsedAmount = 300,
                status = "parsed",
                receivedAt = baseTime,
            )

        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(300, score = 5)) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()

        assertTrue(admitted.isEmpty(), "DB 里已有同金额的其他渠道记录 → 不产出")
        coVerify(exactly = 1) {
            dao.findRecentSameAmount(300, baseTime - 60_000, baseTime + 60_000)
        }
    }

    @Test
    fun `historical windows arriving in either order all produce independently`() = runTest(testDispatcher) {
        for (times in listOf(listOf(-3_600_000L, 0L), listOf(0L, -3_600_000L))) {
            val admitted = mutableListOf<ParsedTransaction>()
            val instance = ScoredAdmissionImpl(dao, ruleSource, AppLogger(mockk(relaxed = true)))
                .also { it.testDispatcher = testDispatcher }
            times.forEach { offset ->
                instance.submit(candidate(5000, score = 5, receivedAt = baseTime + offset)) { admitted += it }
            }
            advanceUntilIdle()
            assertEquals(times.map { baseTime + it }.toSet(), admitted.map { it.capture.receivedAt }.toSet())
            assertEquals(2, admitted.size)
        }
    }

    @Test
    fun `higher score does not move the original receivedAt anchor`() = runTest(testDispatcher) {
        ruleSource = InMemoryNotificationRuleSource(rules(scoringSeconds = 10, dedupSeconds = 0))
        admission = ScoredAdmissionImpl(dao, ruleSource, AppLogger(mockk(relaxed = true)))
            .also { it.testDispatcher = testDispatcher }
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(300, 1, receivedAt = baseTime)) { admitted += it }
        admission.submit(candidate(300, 2, receivedAt = baseTime + 9_000)) { admitted += it }
        admission.submit(candidate(300, 3, receivedAt = baseTime + 18_000)) { admitted += it }
        advanceUntilIdle()
        assertEquals(listOf(2, 3), admitted.map { it.score })
    }

    @Test
    fun `old timer and failing callback cannot consume a later same amount window`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val admitted = mutableListOf<Long>()
        admission.submit(candidate(500, 1)) {
            admitted += it.capture.receivedAt
            gate.await()
            error("old callback failed")
        }
        runCurrent()
        advanceTimeBy(1_000)
        admission.submit(candidate(500, 2, receivedAt = baseTime + 120_000)) {
            admitted += it.capture.receivedAt
        }
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(listOf(baseTime), admitted)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(baseTime, baseTime + 120_000), admitted)
    }

    @Test
    fun `historical same amount claims survive while callback is suspended`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val admitted = mutableListOf<ParsedTransaction>()
        admission.submit(candidate(600, 1, receivedAt = baseTime - 3_600_000)) {
            admitted += it
            gate.await()
        }
        advanceTimeBy(10_100)
        runCurrent()
        admission.submit(candidate(600, 2, receivedAt = baseTime - 3_570_000)) { admitted += it }
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(1, admitted.size)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    // ── 写库在锁外 ──

    @Test
    @DisplayName("产出回调在网络上挂起时，不阻塞另一笔的产出（判定段不占着锁等网络）")
    fun `产出在网络上挂起不阻塞另一笔`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val admittedAmounts = mutableListOf<Int>()

        admission.submit(candidate(100, score = 5)) {
            admittedAmounts += it.amount
            gate.await()
        }
        runCurrent()
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(listOf(100), admittedAmounts, "第一笔已开始产出并卡在网络上")

        admission.submit(candidate(200, score = 5, receivedAt = baseTime + 10_000)) { admittedAmounts += it.amount }
        runCurrent()
        advanceTimeBy(10_100)
        runCurrent()

        assertTrue(admittedAmounts.contains(200), "第二笔的产出不应等第一笔的网络往返")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(100, 200), admittedAmounts)
    }
}
