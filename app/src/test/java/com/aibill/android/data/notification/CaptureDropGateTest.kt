package com.aibill.android.data.notification

import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureDropHandler
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 捕获层早退的归表查表（契约附录 C §11.2/§11.7）。
 *
 * 三件必须成立的事：
 * 1. **计数口径不变**：`block` 与 `sample` 都照旧记一次漏斗（否则历史曲线断掉）；
 * 2. **键口径同源**：查表键 = `CaptureFunnelStats.reportKey(droppedStage(drop))`
 *    （`client_only.capture_dropped_<ch>_<slug>`），与服务端闭集逐字一致；
 * 3. **`forward` 在捕获层视作 `block`**（P1：页面级/无内容转发成本高）。
 */
class CaptureDropGateTest {

    private val t0: Long = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private val stats = CaptureFunnelStatsImpl()
    private val sampler = CaptureSamplerImpl(AppLogger(mockk(relaxed = true)))

    private fun handler(policy: Map<String, String>): CaptureDropHandler = CaptureDropHandlerImpl(
        stats = stats,
        ruleSource = InMemoryNotificationRuleSource(PrefilterTestHarness.rulesWithPolicy(policy)),
        sampler = sampler,
        appLogger = AppLogger(mockk(relaxed = true)),
    )

    private val excludedKey = CaptureFunnelStats.reportKey(CaptureFunnelStats.droppedStage(CaptureDrop.A11Y_EXCLUDED))

    @Test
    @DisplayName("键口径：A11Y_EXCLUDED → client_only.capture_dropped_a11y_excluded（与服务端闭集同源）")
    fun `键口径`() {
        assertEquals("client_only.capture_dropped_a11y_excluded", excludedKey)
    }

    @Test
    @DisplayName("缺键 → 只计数、不采样（今天的行为）")
    fun `缺键只计数`() {
        handler(emptyMap()).onDrop(
            drop = CaptureDrop.A11Y_EXCLUDED,
            packageName = "com.eg.android.AlipayGphone",
            rawText = "结算页 加入购物车 ￥12.00",
            anchors = listOf("购物车"),
            receivedAt = t0,
        )
        assertEquals(1L, stats.snapshot().droppedTotal)
        assertTrue(sampler.drain().isEmpty(), "block 不采样")
    }

    @Test
    @DisplayName("sample → 计数照旧 + 采一条 capture 阶段的 a11y_page 样本（只取命中片段）")
    fun `sample 计数并采样`() {
        val page = "结算页 " + "x".repeat(200) + " 加入购物车 " + "y".repeat(200)
        handler(mapOf(excludedKey to "sample")).onDrop(
            drop = CaptureDrop.A11Y_EXCLUDED,
            packageName = "com.eg.android.AlipayGphone",
            rawText = page,
            anchors = listOf("加入购物车"),
            receivedAt = t0,
        )

        assertEquals(1L, stats.snapshot().droppedTotal, "采样不改变漏斗计数口径")
        val sample = sampler.drain().single()
        assertEquals(SourceChannel.A11Y, sample.channel)
        assertEquals(CaptureKind.A11Y_PAGE, sample.kind)
        assertEquals(CaptureStage.CAPTURE, sample.stage)
        assertEquals(excludedKey, sample.blockedBy)
        assertEquals(page.length, sample.textLen, "text_len = 整页长度")
        assertTrue(sample.text!!.contains("加入购物车"), sample.text!!)
        assertTrue(sample.text!!.length < page.length, "不采整页：${sample.text!!.length} vs ${page.length}")
    }

    @Test
    @DisplayName("通知类渠道（NLS/SMS）→ kind=notification，采全文")
    fun `通知类采样`() {
        val text = "【XX银行】您尾号1234的储蓄卡消费人民币12.00元 优惠券已发放"
        handler(mapOf("client_only.capture_dropped_sms_blank_text" to "sample")).onDrop(
            drop = CaptureDrop.SMS_BLANK_TEXT,
            packageName = "sms:95588",
            rawText = text,
            receivedAt = t0,
        )

        val sample = sampler.drain().single()
        assertEquals(SourceChannel.SMS, sample.channel)
        assertEquals(CaptureKind.NOTIFICATION, sample.kind)
        assertEquals("sms:95588", sample.packageName)
        assertEquals(text, sample.text)
    }

    @Test
    @DisplayName("forward → 捕获层视作 block：只计数、不采样（P1 不支持页面级转发）")
    fun `forward 视作 block`() {
        handler(mapOf(excludedKey to "forward")).onDrop(
            drop = CaptureDrop.A11Y_EXCLUDED,
            packageName = "com.eg.android.AlipayGphone",
            rawText = "结算页 加入购物车",
            anchors = listOf("购物车"),
            receivedAt = t0,
        )
        assertEquals(1L, stats.snapshot().droppedTotal)
        assertTrue(sampler.drain().isEmpty(), "forward 在捕获层不采样")
    }

    @Test
    @DisplayName("拿不到内容（白名单外/无 root）也能采样：只带归因，text=null")
    fun `无内容也归因`() {
        val key = CaptureFunnelStats.reportKey(
            CaptureFunnelStats.droppedStage(CaptureDrop.A11Y_NOT_WHITELISTED),
        )
        handler(mapOf(key to "sample")).onDrop(
            drop = CaptureDrop.A11Y_NOT_WHITELISTED,
            packageName = "com.xunmeng.pinduoduo",
            receivedAt = t0,
        )

        val sample = sampler.drain().single()
        assertEquals("com.xunmeng.pinduoduo", sample.packageName, "包名本身就是最有价值的信息")
        assertEquals(null, sample.text)
        assertEquals(0, sample.textLen)
        assertEquals(key, sample.blockedBy)
    }
}
