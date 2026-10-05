package com.aibill.android.service

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.remote.dto.response.AiParseResponseDto
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.util.AiResultValidator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException

/**
 * 自身通知回环 + 单包 AI 熔断的回归测试
 *
 * 背景（2026-10-01 真机日志）：
 * 本 App 自己会发「已记账 · …」「💰 检测到一笔支出 …」两类通知，
 * 被自己的 NLS 当成新账务再解析一遍，16 次 AI 调用里 3 次是自激，
 * 产生的第二个候选仅靠 60s 金额去重侥幸挡下。
 *
 * 这里只锁 NotificationProcessor 侧的可测逻辑（熔断状态机）。
 * NLS 侧「跳过自身包名」是 Android Service 内的分支，靠
 * NotificationProcessorTest 的既有约定同样无法直接单测，
 * 由真机验证覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationLoopGuardTest {

    private fun newProcessor(
        aiApi: AiApi,
        dao: NotificationRecordDao = mockk(relaxed = true),
    ): NotificationProcessor {
        val context = mockk<android.content.Context>(relaxed = true)
        return NotificationProcessor(
            context = context,
            aiApi = aiApi,
            aiResultValidator = AiResultValidator(mockk(relaxed = true)),
            notificationRecordDao = dao,
            pendingTransactionDao = mockk(relaxed = true),
            userPreferences = mockk(relaxed = true) {
                coEvery { aiParseEnabled } returns kotlinx.coroutines.flow.flowOf(true)
            },
            categoryLearningEngine = mockk(relaxed = true),
            streakTracker = mockk(relaxed = true),
            appLogger = com.aibill.android.util.AppLogger(mockk(relaxed = true)),
            rulesManager = mockk(relaxed = true),
        )
    }

    private fun failingApi(code: Int = 5001): AiApi = mockk<AiApi>().also {
        coEvery { it.parse(any()) } returns ApiResponse(code = code, data = null, message = "AI 解析失败")
    }

    private fun okApi(amount: Int = 1000): AiApi = mockk<AiApi>().also {
        coEvery { it.parse(any()) } returns ApiResponse(
            code = 0,
            data = AiParseResponseDto(
                items = listOf(
                    AiParsedItemDto(
                        type = "expense",
                        amount = amount,
                        categoryId = 1,
                        categoryName = "餐饮",
                        categoryIcon = null,
                        description = "测试商户",
                        date = "2026-10-01",
                        accountId = null,
                        accountName = null,
                        targetAccountId = null,
                        targetAccountName = null,
                    )
                ),
                rawInput = "测试通知",
            ),
            message = "ok",
        )
    }

    private fun item(pkg: String) = NotificationProcessor.Item(
        packageName = pkg,
        title = "测试",
        fullText = "测试通知",
        channel = NotificationProcessor.Channel.NLS,
    )

    // ── 熔断：连续失败后静音 ──

    @Test
    fun `连续失败达到阈值后该包进入熔断`() = runTest {
        val processor = newProcessor(failingApi())
        val pkg = "tv.danmaku.bili"
        assertFalse(processor.isAiMuted(pkg))
        repeat(3) { processor.process(item(pkg)) }
        assertTrue(processor.isAiMuted(pkg), "连续 3 次 5001 后应静音")
    }

    @Test
    fun `熔断有到期时间，到期后恢复`() = runTest {
        val processor = newProcessor(failingApi())
        val pkg = "tv.danmaku.bili"
        repeat(3) { processor.recordAiOutcome(pkg, success = false, now = 1_000L) }
        val mutedUntil = 1_000L + 5 * 60_000L
        assertTrue(processor.isAiMuted(pkg, now = mutedUntil - 1))
        assertFalse(processor.isAiMuted(pkg, now = mutedUntil))
    }

    @Test
    fun `成功一次立即清除熔断状态`() = runTest {
        val processor = newProcessor(failingApi())
        val pkg = "tv.danmaku.bili"
        repeat(3) { processor.recordAiOutcome(pkg, success = false, now = 1_000L) }
        assertTrue(processor.isAiMuted(pkg, now = 2_000L))
        processor.recordAiOutcome(pkg, success = true, now = 3_000L)
        assertFalse(processor.isAiMuted(pkg, now = 4_000L))
    }

    @Test
    fun `熔断按包隔离，互不影响`() = runTest {
        val processor = newProcessor(failingApi())
        repeat(3) { processor.recordAiOutcome("tv.danmaku.bili", success = false, now = 1_000L) }
        assertTrue(processor.isAiMuted("tv.danmaku.bili", now = 2_000L))
        assertFalse(processor.isAiMuted("com.netease.yanxuan", now = 2_000L))
    }

    // ── 熔断只由 AI 失败驱动 ──

    @Test
    fun `AI 成功不触发熔断`() = runTest {
        val processor = newProcessor(okApi())
        val pkg = "com.netease.yanxuan"
        repeat(5) { processor.process(item(pkg)) }
        assertFalse(processor.isAiMuted(pkg), "成功调用不应静音")
    }

    @Test
    fun `AI 异常也计入失败并触发熔断`() = runTest {
        val api = mockk<AiApi>().also {
            coEvery { it.parse(any()) } throws RuntimeException("timeout")
        }
        val processor = newProcessor(api)
        val pkg = "tv.danmaku.bili"
        repeat(3) { processor.process(item(pkg)) }
        assertTrue(processor.isAiMuted(pkg), "网络异常同样应计入熔断计数")
    }

    // ── 边界 ──

    @Test
    fun `失败未达阈值不熔断`() = runTest {
        val processor = newProcessor(failingApi())
        val pkg = "tv.danmaku.bili"
        repeat(2) { processor.recordAiOutcome(pkg, success = false, now = 1_000L) }
        assertFalse(processor.isAiMuted(pkg, now = 2_000L))
    }

    @Test
    fun `未知包默认不熔断`() = runTest {
        val processor = newProcessor(failingApi())
        assertFalse(processor.isAiMuted("com.never.seen.app", now = 1_000L))
    }

    // ── B2：401 待补偿队列 ──

    /** 构造指定 HTTP 状态码的 HttpException */
    private fun httpErr(code: Int): HttpException =
        HttpException(retrofit2.Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun `B2 回归：AI 401 → 落库待补偿而不是丢弃`() = runTest {
        val dao = mockk<NotificationRecordDao>(relaxed = true)
        val inserted = mutableListOf<NotificationRecordEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L
        val processor = newProcessor(mockk<AiApi>().also {
            coEvery { it.parse(any()) } throws httpErr(401)
        }, dao)

        val real = NotificationProcessor.Item(
            packageName = "cmb.pb",
            title = "招商银行",
            fullText = "招商银行 您账户2415发生快捷支付扣款，人民币20.00",
            channel = NotificationProcessor.Channel.NLS,
        )
        processor.process(real)

        val queued = inserted.filter { it.status == NotificationProcessor.STATUS_AUTH_PENDING }
        assertEquals(1, queued.size, "401 必须写入待补偿队列，实际写入=$inserted")
        assertEquals("cmb.pb", queued.first().packageName)
        assertTrue(queued.first().content.contains("人民币20.00"), "待补偿记录必须保留完整原文")
    }

    @Test
    fun `B2 回归：非 401 的 HTTP 错误不入待补偿队列`() = runTest {
        val dao = mockk<NotificationRecordDao>(relaxed = true)
        val inserted = mutableListOf<NotificationRecordEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L
        val processor = newProcessor(mockk<AiApi>().also {
            coEvery { it.parse(any()) } throws httpErr(500)
        }, dao)

        processor.process(
            NotificationProcessor.Item("cmb.pb", "招商银行", "扣款50.00元", NotificationProcessor.Channel.NLS)
        )
        assertTrue(
            inserted.none { it.status == NotificationProcessor.STATUS_AUTH_PENDING },
            "500 不应入待补偿队列（重登也救不回来）",
        )
    }
}
