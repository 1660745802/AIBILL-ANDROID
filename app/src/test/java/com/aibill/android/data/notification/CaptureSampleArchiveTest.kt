package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.CaptureSampleDao
import com.aibill.android.data.local.entity.CaptureSampleEntity
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 采样内容**本地留存**（Room `capture_samples`）的行为护栏。
 *
 * 这层是 2026-10「查漏账」失败的补课：上报通道会**去重**（本窗口一次）、**限流**（每天 200）、
 * 且队列只在内存（进程重启丢），于是「被拦的是什么」在本地**从来没有留存**，
 * 事后结构性不可还原。
 *
 * 四条必须成立：
 * 1. **真内容才落库**（`text != null`）——空行查不出任何东西，只会挤占查询窗口；
 * 2. **不落库的行仍然照常上报**（留存是旁路，不能反噬上报口径）；
 * 3. **落库失败不影响上报**（留存坏掉 ≠ 样本丢掉）；
 * 4. **去重/限流口径一个字没改**（留存完全在锁外，不参与去重与限流判定）。
 */
class CaptureSampleArchiveTest {

    private val t0: Long = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private val logger = AppLogger(mockk(relaxed = true))

    private fun sampler(dao: CaptureSampleDao) = CaptureSamplerImpl(logger, dao)

    private fun a11yInput(text: String?, receivedAt: Long = t0) = CaptureSampleInput(
        channel = SourceChannel.A11Y,
        packageName = "com.tencent.mm",
        kind = CaptureKind.A11Y_PAGE,
        stage = CaptureStage.CAPTURE,
        blockedBy = "client_only.capture_dropped_a11y_not_payment_activity",
        receivedAt = receivedAt,
        rawText = text,
    )

    @Test
    @DisplayName("有内容的样本：入队的同时镜像一行到 capture_samples")
    fun `有内容落库`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        val cap = slot<CaptureSampleEntity>()
        val s = sampler(dao)

        s.record(a11yInput("账单详情 ￥88.00 商户 永辉超市"))

        // 上报队列照旧拿到它（留存不吞样本）
        assertEquals(1, s.drain().size)

        coVerify(exactly = 1) { dao.insert(capture(cap)) }
        val row = cap.captured
        assertEquals("a11y", row.channel, "与上报同一套 channelName")
        assertEquals("com.tencent.mm", row.packageName)
        assertEquals("a11y_page", row.kind)
        assertEquals("capture", row.stage)
        assertEquals("client_only.capture_dropped_a11y_not_payment_activity", row.blockedBy)
        assertEquals(t0, row.receivedAt, "按事件时刻存，不是入库时刻")
        assertTrue(row.text!!.contains("88.00"), row.text!!)
        assertTrue(row.contentHash.startsWith("sha256:"), row.contentHash)
    }

    @Test
    @DisplayName("content_hash 与上报用的那份逐字相同（两边可直接对账）")
    fun `指纹与上报一致`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        val cap = slot<CaptureSampleEntity>()
        val s = sampler(dao)
        s.record(a11yInput("账单详情 ￥88.00"))

        val reported = s.drain().single()
        coVerify { dao.insert(capture(cap)) }
        assertEquals(reported.contentHash, cap.captured.contentHash)
        assertEquals(reported.textLen, cap.captured.textLen)
        assertEquals(reported.text, cap.captured.text)
    }

    @Test
    @DisplayName("无内容的样本（拿不到 root / 白名单外）：不落库，但照常上报")
    fun `无内容不落库但仍上报`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        val s = sampler(dao)

        s.record(a11yInput(null))

        coVerify(exactly = 0) { dao.insert(any()) }
        assertEquals(1, s.drain().size, "留存是旁路，不能改变上报口径")
    }

    @Test
    @DisplayName("落库抛异常：只 warn，样本仍在上报队列里（留存坏掉 ≠ 样本丢掉）")
    fun `落库失败不影响上报`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        coEvery { dao.insert(any()) } throws IllegalStateException("disk full")
        val s = sampler(dao)

        s.record(a11yInput("账单详情 ￥88.00"))

        assertEquals(1, s.drain().size, "落库失败绝不能把样本带走")
    }

    @Test
    @DisplayName("留存与上报共用去重/限流（表不会被刷爆，日上限仍是 200 条）")
    fun `留存与上报共用去重`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        val s = sampler(dao)
        // 同样的原文连采两次：窗口去重在两边都生效 → 上报 1 条、留存 1 行。
        // 刻意不绕过去重：`debounce` 三天 76667 次，同一页会被反复丢弃；
        // 留存若逐次落库，一天就能写爆这张表，反而把有内容的行挤出查询窗口。
        s.record(a11yInput("账单详情 ￥88.00"))
        s.record(a11yInput("账单详情 ￥88.00"))

        assertEquals(1, s.drain().size, "上报口径没变（窗口去重仍生效）")
        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    @DisplayName("不同原文采出不同 content_hash（否则真账会被误判成重复）")
    fun `不同内容指纹不同`() {
        val dao = mockk<CaptureSampleDao>(relaxed = true)
        val s = sampler(dao)
        s.record(a11yInput("账单详情 ￥88.00"))
        s.record(a11yInput("账单详情 ￥128.50"))

        val out = s.drain()
        assertEquals(2, out.size)
        assertNotEquals(out[0].contentHash, out[1].contentHash)
    }
}
