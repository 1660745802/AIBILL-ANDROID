package com.aibill.android.data.notification

import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 被拦内容采样器（契约附录 C §11.3/§11.8）。
 *
 * 钉住四件事：**内容形态**（notification 全文 / a11y 只采片段）、**指纹稳定**、
 * **窗口内去重**、**每天限流**（每规则 20 / 每用户 200）。
 * 发送侧（拆包/重发）在 `CaptureReportTest`。
 */
class CaptureSamplerTest {

    private val logger = AppLogger(mockk(relaxed = true))

    /** 固定「现在」：北京时间 2026-10-14 12:00 = UTC 04:00。分天口径按北京时间。 */
    private val t0: Long = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private fun sampler() = CaptureSamplerImpl(logger, archiveDao())

    private fun notification(
        text: String?,
        blockedBy: String = "client_only.MARKETING",
        packageName: String = "com.tencent.mm",
        receivedAt: Long = t0,
    ) = CaptureSampleInput(
        channel = SourceChannel.NLS,
        packageName = packageName,
        kind = CaptureKind.NOTIFICATION,
        stage = CaptureStage.PREFILTER,
        blockedBy = blockedBy,
        receivedAt = receivedAt,
        rawText = text,
    )

    private fun a11yPage(
        pageText: String?,
        anchors: List<String> = emptyList(),
        blockedBy: String = "client_only.capture_dropped_a11y_no_success_keyword",
        receivedAt: Long = t0,
    ) = CaptureSampleInput(
        channel = SourceChannel.A11Y,
        packageName = "com.eg.android.AlipayGphone",
        kind = CaptureKind.A11Y_PAGE,
        stage = CaptureStage.CAPTURE,
        blockedBy = blockedBy,
        receivedAt = receivedAt,
        rawText = pageText,
        anchors = anchors,
    )

    private fun sha256(text: String): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ────────────────────────── 内容形态 ──────────────────────────

    @Test
    @DisplayName("notification：采全文、超 2000 截断；text_len 是原文长度；redacted=false（P1 采原文）")
    fun `notification 采全文`() {
        val s = sampler()
        s.record(notification("A".repeat(2500)))
        val sample = s.drain().single()

        assertEquals(2000, sample.text?.length ?: -1)
        assertEquals(2500, sample.textLen, "text_len 必须是原文长度（截断前）")
        assertEquals(CaptureKind.NOTIFICATION, sample.kind)
        assertEquals(CaptureStage.PREFILTER, sample.stage)
        assertEquals(false, sample.redacted, "P1 默认采原文（§11.5）")
        assertEquals(sha256("A".repeat(2000)), sample.contentHash, "指纹覆盖实际发出的文本")
    }

    @Test
    @DisplayName("a11y_page：不采整页，只采命中词前后各 30 字；text_len 是整页长度")
    fun `a11y 只采命中片段`() {
        val page = "前言".repeat(60) + "支付成功" + "后语".repeat(60)
        val s = sampler()
        s.record(a11yPage(page, anchors = listOf("支付成功")))
        val sample = s.drain().single()

        val text = sample.text!!
        assertTrue(text.contains("支付成功"), text)
        assertEquals(30 + 4 + 30, text.length, "只有命中处 ±30 字：$text")
        assertEquals(page.length, sample.textLen, "text_len 是整页长度，不是片段长度")
        // 远端文本不得出现（否则就成「采整页」了）：页首 40 字不该出现在片段里
        assertFalse(text.contains(page.take(40)), "片段里混入远端文本：$text")
    }

    @Test
    @DisplayName("a11y_page：没有命中锚点时退化为金额候选片段；都没有 → text=null（不编造）")
    fun `a11y 无锚点退化为金额`() {
        val page = "账单明细 " + "x".repeat(120) + " ¥12.00 " + "y".repeat(120)
        val withAmount = sampler().also { it.record(a11yPage(page)) }.drain().single()
        assertTrue(withAmount.text!!.contains("¥12.00"), withAmount.text!!)
        assertTrue(withAmount.text!!.length < page.length)

        val nothing = sampler().also { it.record(a11yPage("纯文本没有任何金额与锚点")) }.drain().single()
        assertNull(nothing.text)
        assertEquals("纯文本没有任何金额与锚点".length, nothing.textLen)
    }

    @Test
    @DisplayName("拿不到内容（白名单外/无 root 这类早退）→ text=null、text_len=0，仍然采样归因")
    fun `无内容样本`() {
        val s = sampler()
        s.record(a11yPage(null, blockedBy = "client_only.capture_dropped_a11y_not_whitelisted"))
        val sample = s.drain().single()
        assertNull(sample.text)
        assertEquals(0, sample.textLen)
        assertEquals(sha256(""), sample.contentHash, "空内容也有稳定指纹（服务端仍按 blocked_by 归因）")
        assertEquals("client_only.capture_dropped_a11y_not_whitelisted", sample.blockedBy)
    }

    // ────────────────────────── 指纹与去重 ──────────────────────────

    @Test
    @DisplayName("content_hash 稳定：同文本同 hash；指纹随文本变，且与 blocked_by 无关")
    fun `content_hash 稳定`() {
        val a = sampler().also { it.record(notification("微信支付 ¥20.00")) }.drain().single()
        val b = sampler().also { it.record(notification("微信支付 ¥20.00")) }.drain().single()
        val c = sampler().also { it.record(notification("微信支付 ¥21.00")) }.drain().single()
        assertEquals(a.contentHash, b.contentHash)
        assertNotEquals(a.contentHash, c.contentHash)
        assertEquals(sha256("微信支付 ¥20.00"), a.contentHash)
    }

    @Test
    @DisplayName("同 (content_hash, blocked_by) 本窗口只发一次；drain 后新窗口可以再采")
    fun `窗口内去重`() {
        val s = sampler()
        s.record(notification("同一条广告"))
        s.record(notification("同一条广告"))
        assertEquals(1, s.drain().size)
        assertEquals(1L, s.counters().duplicates)

        // 新窗口：允许再采（服务端 UNIQUE(user_id, content_hash, blocked_by) 仍会兜底）
        s.record(notification("同一条广告"))
        assertEquals(1, s.drain().size)
    }

    @Test
    @DisplayName("同一内容被不同规则拦下 = 两条样本（去重键含 blocked_by）")
    fun `不同规则不去重`() {
        val s = sampler()
        s.record(notification("同一条广告", blockedBy = "client_only.MARKETING"))
        s.record(notification("同一条广告", blockedBy = "client_only.PROMO_SENTENCE"))
        assertEquals(2, s.drain().size)
    }

    // ────────────────────────── 每天限流 ──────────────────────────

    @Test
    @DisplayName("每 (package, rule) 每天 20 条，超出丢弃并计数")
    fun `每规则每天上限`() {
        val s = sampler()
        repeat(25) { i -> s.record(notification("广告文案 #$i")) }
        assertEquals(CaptureSampler.MAX_PER_RULE_PER_DAY, s.drain().size)
        assertEquals(5L, s.counters().droppedPerRule)
    }

    @Test
    @DisplayName("每用户每天 200 条（跨规则），超出丢弃并计数")
    fun `每用户每天上限`() {
        val s = sampler()
        // 11 个包 × 20 条 = 220 条不同内容 → 规则级不拦，用户级放到第 200 条为止
        repeat(11) { pkg ->
            repeat(20) { i -> s.record(notification("促销 #$pkg-$i", packageName = "pkg.$pkg")) }
        }
        assertEquals(CaptureSampler.MAX_PER_USER_PER_DAY, s.drain().size)
        assertEquals(20L, s.counters().droppedTotal)
        assertEquals(0L, s.counters().droppedPerRule)
    }

    @Test
    @DisplayName("跨天重置：昨天的配额不占今天")
    fun `换天重置`() {
        val s = sampler()
        repeat(20) { i -> s.record(notification("昨天的广告 #$i")) }
        assertEquals(20, s.drain().size)

        // 第 21 条仍在昨天 → 被规则上限拦下
        s.record(notification("昨天第 21 条"))
        assertEquals(0, s.drain().size)
        assertEquals(1L, s.counters().droppedPerRule)

        // 换到第二天：同包同规则重新放行
        val tomorrow = t0 + 24 * 60 * 60 * 1000L
        s.record(notification("今天的广告", receivedAt = tomorrow))
        assertEquals(1, s.drain().size)
    }
}
