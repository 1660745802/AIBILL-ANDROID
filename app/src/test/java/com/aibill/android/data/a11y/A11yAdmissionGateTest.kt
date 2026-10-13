package com.aibill.android.data.a11y

import com.aibill.android.domain.a11y.A11yMoment
import com.aibill.android.domain.a11y.Admission
import com.aibill.android.domain.a11y.DropReason
import com.aibill.android.domain.a11y.PageEventKind
import com.aibill.android.domain.a11y.PageSnapshot
import com.aibill.android.domain.a11y.PageToken
import com.aibill.android.domain.a11y.RetryAbandonReason
import com.aibill.android.domain.a11y.RetryOutcome
import com.aibill.android.domain.a11y.ScanDecision
import com.aibill.android.domain.notification.SourceChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [A11yAdmissionGateImpl] 的状态转移用例——**纯 JVM，喂 [A11yMoment] 值，无需 FakeClock**。
 *
 * 每个用例 `newGate()` 独立实例，避免跨用例状态污染。钉住 C01 的五条决定：
 * 两段式（先节流后遍历）、C 语义页面重发、重试一次、注入时间、节流窗清理。
 */
class A11yAdmissionGateTest {

    private val today = LocalDate.of(2026, 10, 13)

    private fun newGate() = A11yAdmissionGateImpl()
    private fun at(ms: Long) = A11yMoment(ms, today)
    private fun pageToken(name: String = "com.tencent.mm|1|PayActivity") = PageToken(name)

    private fun snapshot(
        token: PageToken,
        amount: String? = "¥32.50",
        merchant: String? = "星巴克",
        success: String? = "支付成功",
        exclude: String? = null,
        texts: List<String> = listOf("支付成功", "¥32.50", "星巴克"),
        packageName: String = "com.tencent.mm",
    ) = PageSnapshot(token, packageName, texts, success, exclude, amount, merchant)

    private fun dropped(admission: Admission): Admission.Dropped {
        assertTrue(admission is Admission.Dropped, "期望 Dropped，实际 $admission")
        return admission as Admission.Dropped
    }

    private fun captured(admission: Admission): Admission.Captured {
        assertTrue(admission is Admission.Captured, "期望 Captured，实际 $admission")
        return admission as Admission.Captured
    }

    // ── phase 1：节流 ──

    @Test
    fun `G4 1dot5s 扫描节流：窗口内 Throttled，窗口外 Scan`() {
        val gate = newGate()
        val t = pageToken()
        assertEquals(ScanDecision.Scan, gate.beforeScan(t, PageEventKind.STATE, at(1_000)))
        assertEquals(ScanDecision.Throttled, gate.beforeScan(t, PageEventKind.STATE, at(2_000)))
        assertEquals(ScanDecision.Scan, gate.beforeScan(t, PageEventKind.STATE, at(3_000)))
    }

    @Test
    fun `G3 3s 仅对 CONTENT 生效，且 G4 会计入此前 STATE 的时间戳`() {
        val gate = newGate()
        val t = pageToken()
        assertEquals(ScanDecision.Scan, gate.beforeScan(t, PageEventKind.CONTENT, at(0)))
        // CONTENT 1s：G3 命中
        assertEquals(ScanDecision.Throttled, gate.beforeScan(t, PageEventKind.CONTENT, at(1_000)))
        // STATE 2s：跳过 G3；G4 (2s) 已过 1.5s → Scan
        assertEquals(ScanDecision.Scan, gate.beforeScan(t, PageEventKind.STATE, at(2_000)))
        // CONTENT 2.5s：G3 仍命中（距 0 未满 3s）
        assertEquals(ScanDecision.Throttled, gate.beforeScan(t, PageEventKind.CONTENT, at(2_500)))
        // CONTENT 3.1s：G3 过，但 G4 距上次 STATE(2s) 仅 1.1s → Throttled
        assertEquals(ScanDecision.Throttled, gate.beforeScan(t, PageEventKind.CONTENT, at(3_100)))
    }

    @Test
    fun `同页 STATE+CONTENT 只扫一次`() {
        val gate = newGate()
        val t = pageToken()
        assertEquals(ScanDecision.Scan, gate.beforeScan(t, PageEventKind.STATE, at(0)))
        assertEquals(ScanDecision.Throttled, gate.beforeScan(t, PageEventKind.CONTENT, at(100)))
    }

    // ── phase 2：判定链 ──

    @Test
    fun `未命中成功词与命中排除词各自成桶`() {
        val gate = newGate()
        assertEquals(
            DropReason.NOT_SUCCESS_PAGE,
            dropped(gate.admit(snapshot(pageToken("t1"), success = null), at(0))).reason,
        )
        assertEquals(
            DropReason.EXCLUDED,
            dropped(gate.admit(snapshot(pageToken("t2"), exclude = "购物车"), at(10))).reason,
        )
    }

    @Test
    fun `金额边界：0dot01 与 100000dot00 放行，0 与超额拒绝，非数字不可解析`() {
        val gate = newGate()
        val c1 = captured(gate.admit(snapshot(pageToken("t1"), amount = "¥0.01"), at(0)))
        assertEquals(SourceChannel.A11Y, c1.capture.channel)
        captured(gate.admit(snapshot(pageToken("t2"), amount = "¥100000.00"), at(10)))
        assertEquals(
            DropReason.AMOUNT_OUT_OF_RANGE,
            dropped(gate.admit(snapshot(pageToken("t3"), amount = "¥0.00"), at(20))).reason,
        )
        assertEquals(
            DropReason.AMOUNT_OUT_OF_RANGE,
            dropped(gate.admit(snapshot(pageToken("t4"), amount = "¥100000.01"), at(30))).reason,
        )
        assertEquals(
            DropReason.AMOUNT_UNPARSABLE,
            dropped(gate.admit(snapshot(pageToken("t5"), amount = "¥abc"), at(40))).reason,
        )
    }

    @Test
    fun `历史日期切片窗：金额邻域含昨天则拦截`() {
        val gate = newGate()
        val texts = listOf("支付成功", "昨天 14:30", "¥32.50", "星巴克")
        val d = dropped(
            gate.admit(snapshot(pageToken("t1"), amount = "¥32.50", texts = texts), at(0)),
        )
        assertEquals(DropReason.HISTORY_DATE, d.reason)
    }

    @Test
    fun `G1 8s 防抖：同 summary 窗口内拦截，窗口外放行`() {
        val gate = newGate()
        val t = pageToken()
        captured(gate.admit(snapshot(t), at(0)))
        assertEquals(DropReason.DEBOUNCE_8S, dropped(gate.admit(snapshot(t), at(4_000))).reason)
        // 9s 后同 summary：G1 过（但可能命中 G2——见下一条，故用不同 token 只看 G1 放行）
    }

    @Test
    fun `C 语义：同页同内容 30s 内页面重发拦截，同页异内容放行`() {
        val gate = newGate()
        val t = pageToken()
        captured(gate.admit(snapshot(t), at(0)))
        // 9s：过 G1，命中 G2（同页同内容）
        assertEquals(DropReason.PAGE_DEDUP_30S, dropped(gate.admit(snapshot(t), at(9_000))).reason)
        // 9.5s：同页但金额不同 → summary 不同 → 放行（同页第二笔真交易不得误杀）
        captured(gate.admit(snapshot(t, amount = "¥18.00", merchant = "瑞幸"), at(9_500)))
    }

    @Test
    fun `同额不同页不本地判重：交易级同金额归口下游`() {
        val gate = newGate()
        // 两个不同 pageToken、相同金额/商家，间隔过 8s 防抖 → 各自放行
        captured(gate.admit(snapshot(pageToken("pageA")), at(0)))
        captured(gate.admit(snapshot(pageToken("pageB")), at(9_000)))
    }

    @Test
    fun `Captured 产出完整预筛输入（channel title fullText pageText）`() {
        val gate = newGate()
        val c = captured(gate.admit(snapshot(pageToken("t1")), at(0))).capture
        assertEquals(SourceChannel.A11Y, c.channel)
        assertEquals("支付成功", c.title)
        assertTrue(c.fullText.contains("¥32.50"))
        assertTrue(c.fullText.contains("星巴克"))
        assertNotNull(c.pageText)
        assertEquals(0L, c.receivedAt)
    }

    // ── 重试 ──

    @Test
    fun `重试：无金额时挂一次，pending 期间不再挂第二次`() {
        val gate = newGate()
        val t = pageToken()
        val noAmount = snapshot(t, amount = null)
        val r1 = dropped(gate.admit(noAmount, at(0)))
        assertEquals(DropReason.NO_AMOUNT, r1.reason)
        assertNotNull(r1.retry)
        val r2 = dropped(gate.admit(noAmount, at(100)))
        assertEquals(DropReason.NO_AMOUNT, r2.reason)
        assertNull(r2.retry)
    }

    @Test
    fun `重试绕过 G4：onRetryDue 不受 1dot5s 扫描节流约束`() {
        val gate = newGate()
        val t = pageToken()
        val texts = listOf("支付成功", "星巴克臻选店", "订单号 12345")
        gate.beforeScan(t, PageEventKind.STATE, at(0))
        // 无金额 → 挂重试（anchor 来自 texts）
        dropped(gate.admit(snapshot(t, amount = null, texts = texts), at(0)))
        // 100ms 内（G4 窗口内）onRetryDue 仍应命中
        val outcome = gate.onRetryDue(t, texts, at(100))
        assertTrue(outcome is RetryOutcome.Reread, "期望 Reread，实际 $outcome")
    }

    @Test
    fun `重试绕不过 G1：Reread 后同 summary 8s 内仍被防抖拦截`() {
        val gate = newGate()
        val t = pageToken()
        val texts = listOf("支付成功", "星巴克臻选店", "订单号 12345")
        gate.beforeScan(t, PageEventKind.STATE, at(0))
        // 先放行一笔（写入 G1 槽）
        captured(gate.admit(snapshot(t, amount = "¥32.50", merchant = "星巴克臻选店", texts = texts), at(0)))
        // 挂重试并 Reread
        dropped(gate.admit(snapshot(t, amount = null, texts = texts), at(1_000)))
        assertTrue(gate.onRetryDue(t, texts, at(1_100)) is RetryOutcome.Reread)
        // Reread 后同 summary（¥32.50 星巴克臻选店）在 8s 内 → 防抖
        val d = dropped(
            gate.admit(snapshot(t, amount = "¥32.50", merchant = "星巴克臻选店", texts = texts), at(1_200)),
        )
        assertEquals(DropReason.DEBOUNCE_8S, d.reason)
    }

    @Test
    fun `重试：切页后作废（TOKEN_STALE）`() {
        val gate = newGate()
        val a = pageToken("pkg|1|A")
        val b = pageToken("pkg|2|B")
        val texts = listOf("支付成功", "星巴克", "订单号 1")
        gate.beforeScan(a, PageEventKind.STATE, at(0))
        dropped(gate.admit(snapshot(a, amount = null, texts = texts), at(0)))
        gate.beforeScan(b, PageEventKind.STATE, at(1_000))
        val outcome = gate.onRetryDue(a, texts, at(1_500))
        assertTrue(outcome is RetryOutcome.Abandoned && outcome.reason == RetryAbandonReason.TOKEN_STALE)
    }

    @Test
    fun `重试：anchor 不匹配作废（ANCHOR_MISSED）`() {
        val gate = newGate()
        val t = pageToken()
        gate.beforeScan(t, PageEventKind.STATE, at(0))
        dropped(gate.admit(snapshot(t, amount = null, texts = listOf("支付成功", "星巴克臻选店", "订单号 12345")), at(0)))
        val outcome = gate.onRetryDue(t, listOf("完全", "不同的", "页面文本"), at(600))
        assertTrue(outcome is RetryOutcome.Abandoned && outcome.reason == RetryAbandonReason.ANCHOR_MISSED)
    }

    @Test
    fun `重试：无挂起时作废（NOT_PENDING）`() {
        val gate = newGate()
        // 先让该 token 成为当前页（否则命中 TOKEN_STALE）
        val t = pageToken("never-pending")
        gate.beforeScan(t, PageEventKind.STATE, at(0))
        val outcome = gate.onRetryDue(t, listOf("x"), at(10))
        assertTrue(outcome is RetryOutcome.Abandoned && outcome.reason == RetryAbandonReason.NOT_PENDING)
    }
}
