package com.aibill.android.data.notification

import com.aibill.android.BuildConfig
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.CaptureReportRequest
import com.aibill.android.data.remote.dto.request.CaptureSampleDto
import com.aibill.android.data.remote.dto.request.FilterReportRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CaptureReportResponseDto
import com.aibill.android.data.remote.dto.response.FilterReportResponseDto
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `POST /api/ai/capture-report` 的上报（契约附录 C §11.3）。
 *
 * 钉住：**请求体形状**（契约字段名 + null 省略）、**拆包 ≤50**、
 * **失败原窗口重发**、以及「与 ⑤ 计数上报同一次窗口、互不干扰」。
 */
class CaptureReportTest {

    private val aiApi = mockk<AiApi>()
    private val logger = AppLogger(mockk(relaxed = true))

    private val t0: Long = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        .toInstant().toEpochMilli()

    private val captureRequests = mutableListOf<CaptureReportRequest>()
    private val filterRequests = mutableListOf<FilterReportRequest>()

    /** [failCapture] 返回 true 时 captureReport 抛 IOException（模拟离线）。 */
    private fun reporter(
        sampler: CaptureSampler,
        stats: PrefilterStatsImpl = PrefilterStatsImpl(),
        failCapture: () -> Boolean = { false },
    ): PrefilterReportImpl {
        val captureSlot = slot<CaptureReportRequest>()
        coEvery { aiApi.captureReport(capture(captureSlot)) } answers {
            captureRequests += captureSlot.captured
            if (failCapture()) throw IOException("offline")
            ApiResponse(code = 0, data = CaptureReportResponseDto(accepted = captureSlot.captured.samples.size), message = "ok")
        }
        val filterSlot = slot<FilterReportRequest>()
        coEvery { aiApi.filterReport(capture(filterSlot)) } answers {
            filterRequests += filterSlot.captured
            ApiResponse(code = 0, data = FilterReportResponseDto(accepted = 1), message = "ok")
        }
        return PrefilterReportImpl(
            stats = stats,
            captureFunnelStats = CaptureFunnelStatsImpl(),
            sampler = sampler,
            aiApi = aiApi,
            appLogger = logger,
        )
    }

    private fun sample(
        text: String,
        blockedBy: String = "client_only.capture_dropped_a11y_excluded",
        packageName: String = "com.eg.android.AlipayGphone",
        channel: SourceChannel = SourceChannel.A11Y,
        kind: CaptureKind = CaptureKind.A11Y_PAGE,
        tier: Int? = null,
    ) = CaptureSampleInput(
        channel = channel,
        packageName = packageName,
        kind = kind,
        stage = CaptureStage.CAPTURE,
        blockedBy = blockedBy,
        receivedAt = t0,
        rawText = text,
        anchors = listOf("购物车"),
        tier = tier,
    )

    @Test
    @DisplayName("请求体形状：契约字段名 / null 省略 / 窗口是北京墙钟串 / received_at 带 +08:00")
    fun `请求体形状`() = runTest {
        val sampler = CaptureSamplerImpl(logger)
        sampler.record(sample("结算页 加入购物车 ￥12.00"))
        reporter(sampler).report(t0)

        val req = captureRequests.single()
        assertEquals(BuildConfig.VERSION_NAME, req.clientVersion)
        assertEquals("2026-10-14 12:00:00", req.windowEnd, "窗口串与 ⑤ 同口径（北京时间，无时区）")
        assertTrue(req.windowStart.startsWith("2026-10-14 "), req.windowStart)

        val dto = req.samples.single()
        assertEquals("a11y", dto.channel)
        assertEquals("com.eg.android.AlipayGphone", dto.packageName)
        assertEquals("a11y_page", dto.kind)
        assertEquals("capture", dto.stage)
        assertEquals("client_only.capture_dropped_a11y_excluded", dto.blockedBy)
        assertEquals("2026-10-14T12:00:00+08:00", dto.receivedAt)
        assertTrue(dto.contentHash.startsWith("sha256:"), dto.contentHash)
        assertEquals(false, dto.redacted, "P1 采原文")
        assertEquals("结算页 加入购物车 ￥12.00".length, dto.textLen)
        assertEquals("结算页 加入购物车 ￥12.00", dto.text, "片段命中锚点且窗口覆盖整段（页短）")

        // 线格式：Moshi 省略 null（tier 未给）→ 与服务端 optional 对齐
        val json = Moshi.Builder().build().adapter(CaptureReportRequest::class.java).toJson(req)
        for (key in listOf("\"client_version\"", "\"window_start\"", "\"window_end\"", "\"samples\"",
            "\"channel\"", "\"package\"", "\"kind\"", "\"stage\"", "\"blocked_by\"", "\"received_at\"",
            "\"text\"", "\"text_len\"", "\"content_hash\"", "\"redacted\"")) {
            assertTrue(json.contains(key), "缺字段 $key：$json")
        }
        assertFalse(json.contains("\"tier\""), "null 字段不应出现：$json")
    }

    @Test
    @DisplayName("样本 ≤50/请求：60 条拆成 50 + 10（不截断）")
    fun `拆包`() = runTest {
        val sampler = CaptureSamplerImpl(logger)
        // 3 个包 × 20 条：绕开「每 (package, rule) 每天 20 条」的限流（那是采样器的职责，另有用例）
        repeat(60) { i -> sampler.record(sample("第 $i 条被拦页面 加入购物车", packageName = "pkg.${i % 3}")) }
        reporter(sampler).report(t0)

        assertEquals(2, captureRequests.size)
        assertEquals(listOf(50, 10), captureRequests.map { it.samples.size })
        // 两次请求的窗口边界一致（同一窗口的拆包，不是两个窗口）
        assertEquals(captureRequests[0].windowEnd, captureRequests[1].windowEnd)
        assertEquals(60, captureRequests.sumOf { it.samples.size })
    }

    @Test
    @DisplayName("失败原窗口重发：失败期间不 drain 新样本；下次先用原窗口重发")
    fun `失败重发`() = runTest {
        val sampler = CaptureSamplerImpl(logger)
        var offline = true
        val impl = reporter(sampler, failCapture = { offline })

        sampler.record(sample("第一条 加入购物车"))
        impl.report(t0)
        assertEquals(1, captureRequests.size, "第一次尝试应发出（但失败）")

        // 仍离线：重发旧窗口又失败 → 必须**提前返回**，不 drain 新样本
        sampler.record(sample("第二条 加入购物车"))
        impl.report(t0 + 30_000L)
        assertEquals(2, captureRequests.size)
        assertEquals(1, sampler.drain().size, "重发失败时不得 drain 新样本")
        sampler.record(sample("第二条 加入购物车"))   // 放回（drain 只是取走）

        // 恢复：先重发原窗口，再发新窗口（同一次 report）
        offline = false
        impl.report(t0 + 60_000L)
        assertEquals(4, captureRequests.size)
        assertEquals(captureRequests[0].windowEnd, captureRequests[1].windowEnd, "重发必须用原窗口边界")
        assertEquals(
            captureRequests[0].samples.single().contentHash,
            captureRequests[1].samples.single().contentHash,
            "重发的就是同一条样本",
        )
        assertTrue(sampler.drain().isEmpty(), "新窗口已随本次 report 发出")
    }

    @Test
    @DisplayName("与 ⑤ 计数上报同一次窗口：两个端点各自发出，互不影响")
    fun `与计数并存`() = runTest {
        val stats = PrefilterStatsImpl()
        stats.recordBlocked(SourceChannel.A11Y, "client_only.capture_dropped_a11y_excluded")
        val sampler = CaptureSamplerImpl(logger)
        sampler.record(sample("结算页 加入购物车"))

        reporter(sampler, stats).report(t0)

        assertEquals(1, filterRequests.size)
        assertEquals(1, captureRequests.size)
        assertEquals(1, captureRequests.single().samples.size)
    }

    @Test
    @DisplayName("空样本不发请求（服务端 samples 至少 1 条）")
    fun `空样本不发`() = runTest {
        reporter(CaptureSamplerImpl(logger)).report(t0)
        assertTrue(captureRequests.isEmpty())
    }

    /** DTO 只有 wire 形态，单独钉一下 tier 会带上（precheck 归表 sample 时会用到）。 */
    @Test
    @DisplayName("tier 非空时进入请求体（precheck.* 样本带档位）")
    fun `tier 上送`() = runTest {
        val sampler = CaptureSamplerImpl(logger)
        sampler.record(
            sample(
                "验证码 123456",
                blockedBy = "precheck.strong_noise",
                channel = SourceChannel.SMS,
                kind = CaptureKind.NOTIFICATION,
                packageName = "sms:10086",
                tier = 0,
            ),
        )
        reporter(sampler).report(t0)
        val dto: CaptureSampleDto = captureRequests.single().samples.single()
        assertEquals(0, dto.tier)
        assertEquals("sms:10086", dto.packageName)
        assertEquals("notification", dto.kind)
        assertEquals("precheck.strong_noise", dto.blockedBy)
    }
}
