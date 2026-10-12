package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.ParseFeedbackRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.notification.FeedbackItem
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * AI 反馈发送（跨端契约 §2）。
 *
 * 钉住四件容易做错的事：
 * 1. **没有锚点就 no-op**（手工记账/外部静默入账/服务端预筛挡下的形态）——不编造 id；
 * 2. 删除 = `final_items` **空数组**；修正 = 长度 1 的最终态；
 * 3. 发送成功后**清锚点**（同一笔反复改不重复上报）；日志不存在(3002) 也清（不可恢复）；
 * 4. 网络异常**保留锚点**（下次改动再带上），且绝不上抛给用户操作。
 */
class ParseFeedbackImplTest {

    private val aiApi: AiApi = mockk()
    private val dao: PendingTransactionDao = mockk(relaxed = true)
    private val impl = ParseFeedbackImpl(aiApi, dao, AppLogger(mockk(relaxed = true)))

    private fun row(parseLogId: Long?) = PendingTransactionEntity(
        clientId = "c1",
        amount = 3250,
        date = "2026-10-12",
        type = "expense",
        source = "app_notification",
        clientCreatedAt = "2026-10-12T10:00:00Z",
        serverTransactionId = 42,
        parseLogId = parseLogId,
    )

    private fun item() = FeedbackItem(
        type = "expense",
        amount = 3250,
        categoryId = 3,
        description = "午饭",
        accountId = 7,
        accountName = "招商银行",
    )

    @Test
    @DisplayName("有锚点 → 回传最终态（含 account_name），且发送成功后清锚点")
    fun reportsAndClearsAnchor() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = 777L)
        val body = slot<ParseFeedbackRequest>()
        coEvery { aiApi.parseFeedback(capture(body)) } returns ApiResponse(0, null, "")

        impl.reportCorrected(42, item(), modified = false)

        assertEquals(777L, body.captured.parseLogId)
        assertEquals(1, body.captured.finalItems.size)
        assertEquals(3250, body.captured.finalItems.first().amount)
        // modified 只能照调用方传入，不得再被硬编码成 true
        assertFalse(body.captured.modified, "modified 必须透传调用方的判断")
        // account_name 必须与 account_id 成对带上，否则服务端账户修正学习永不触发
        assertEquals("招商银行", body.captured.finalItems.first().accountName)
        coVerify(exactly = 1) { dao.clearParseLogId(42) }
    }

    @Test
    @DisplayName("modified=true 也原样透传（编辑页确证有变化时）")
    fun modifiedTrueIsPassedThrough() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = 777L)
        val body = slot<ParseFeedbackRequest>()
        coEvery { aiApi.parseFeedback(capture(body)) } returns ApiResponse(0, null, "")

        impl.reportCorrected(42, item(), modified = true)

        assertTrue(body.captured.modified)
    }

    @Test
    @DisplayName("删除 → final_items 为空数组，且 modified 恒为 true（删除本身就是变化）")
    fun deleteSendsEmptyItems() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = 888L)
        val body = slot<ParseFeedbackRequest>()
        coEvery { aiApi.parseFeedback(capture(body)) } returns ApiResponse(0, null, "")

        impl.reportDeleted(42)

        assertTrue(body.captured.finalItems.isEmpty(), "删除必须传空数组")
        assertTrue(body.captured.modified, "删除路径 modified 恒 true")
        coVerify(exactly = 1) { dao.clearParseLogId(42) }
    }

    @Test
    @DisplayName("没有锚点（非 AI 路径/已反馈过）→ 静默跳过，不发请求")
    fun noAnchorIsNoOp() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = null)

        impl.reportCorrected(42, item(), modified = true)

        coVerify(exactly = 0) { aiApi.parseFeedback(any()) }
    }

    @Test
    @DisplayName("找不到本地行（手工记账）→ 静默跳过")
    fun missingRowIsNoOp() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns null

        impl.reportDeleted(42)

        coVerify(exactly = 0) { aiApi.parseFeedback(any()) }
    }

    @Test
    @DisplayName("网络异常 → 保留锚点（下次改动再带），不上抛")
    fun networkFailureKeepsAnchor() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = 999L)
        coEvery { aiApi.parseFeedback(any()) } throws IOException("timeout")

        impl.reportCorrected(42, item(), modified = true)   // 不抛

        coVerify(exactly = 0) { dao.clearParseLogId(any()) }
    }

    @Test
    @DisplayName("日志不存在(3002，不可恢复) → 清锚点，避免每次改动都白试")
    fun logNotFoundClearsAnchor() = runTest {
        coEvery { dao.findByServerTransactionId(42) } returns row(parseLogId = 111L)
        coEvery { aiApi.parseFeedback(any()) } returns ApiResponse(3002, null, "日志不存在")

        impl.reportCorrected(42, item(), modified = true)

        coVerify(exactly = 1) { dao.clearParseLogId(42) }
    }
}
