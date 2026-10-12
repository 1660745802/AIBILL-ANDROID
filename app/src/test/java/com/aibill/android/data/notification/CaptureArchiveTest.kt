package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureArchive
import com.aibill.android.domain.notification.CapturedText
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [CaptureArchive] 的接口级测试：**捕获 → 重放 → 终态**。
 *
 * 这是产品承诺「不漏账 / 失败绝不静默」的落点：解析失败与 401 都必须留下原文。
 */
class CaptureArchiveTest {

    private val dao: NotificationRecordDao = mockk(relaxed = true)
    private lateinit var archive: CaptureArchiveImpl

    private val receivedAtMs = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        archive = CaptureArchiveImpl(dao, AppLogger(mockk(relaxed = true)))
    }

    private fun captureOf(
        packageName: String = "cmb.pb",
        title: String = "招商银行",
        text: String = "您账户2415发生快捷支付扣款，人民币50.00",
    ) = Capture(SourceChannel.NLS, packageName, title, text, receivedAtMs)

    /** 注册 insert 捕获，返回按写入顺序收集的行。 */
    private fun captureInserted(): MutableList<NotificationRecordEntity> {
        val inserted = mutableListOf<NotificationRecordEntity>()
        coEvery { dao.insert(capture(inserted)) } returns 1L
        return inserted
    }

    // ── 捕获 ──

    @Test
    @DisplayName("解析失败 → 落一行 raw：原文进 content，金额/分类留空")
    fun `解析失败落 raw`() = runTest {
        val inserted = captureInserted()
        archive.captureUnparsed(captureOf(), reason = "code=5001")

        assertEquals(1, inserted.size)
        with(inserted.single()) {
            assertEquals(CaptureArchive.STATUS_RAW, status)
            assertEquals("cmb.pb", packageName)
            assertEquals("招商银行", title)
            assertEquals("您账户2415发生快捷支付扣款，人民币50.00", content)
            assertNull(parsedAmount, "没解析出金额就必须留空，不能编一个")
            assertNull(parsedType)
            assertNull(parsedDescription)
            assertEquals(receivedAtMs, receivedAt)
        }
    }

    @Test
    @DisplayName("401 → 落一行 auth_pending（与 raw 是不同语义：等重登重放）")
    fun `401 落 auth_pending`() = runTest {
        val inserted = captureInserted()
        archive.captureAuthPending(captureOf())

        assertEquals(1, inserted.size)
        assertEquals(CaptureArchive.STATUS_AUTH_PENDING, inserted.single().status)
        assertEquals("您账户2415发生快捷支付扣款，人民币50.00", inserted.single().content)
    }

    @Test
    @DisplayName("空标题写 null（不回写空串）")
    fun `空标题写 null`() = runTest {
        val inserted = captureInserted()
        archive.captureUnparsed(captureOf(title = "   "), reason = "http=500")
        assertNull(inserted.single().title)
    }

    @Test
    @DisplayName("落库失败只记日志，不向上抛（捕获兜底不能自己崩掉）")
    fun `落库失败不抛`() = runTest {
        coEvery { dao.insert(any()) } throws RuntimeException("db down")
        archive.captureUnparsed(captureOf(), reason = "IOException")
        archive.captureAuthPending(captureOf())
        // 两次写入都尝试过、且异常都被吞掉（不向上抛 → 捕获层自己不会成为崩溃点）
        coVerify(exactly = 2) { dao.insert(any()) }
    }

    // ── 重放 → 终态 ──

    private fun pendingRow(id: Long) = NotificationRecordEntity(
        id = id,
        packageName = "cmb.pb",
        title = null,
        content = "扣款50.00元",
        status = CaptureArchive.STATUS_AUTH_PENDING,
        receivedAt = receivedAtMs,
    )

    @Test
    @DisplayName("重放把原文逐条交回，随后标终态（不再参与重放）")
    fun `重放并标终态`() = runTest {
        coEvery { dao.findAuthPending(any()) } returns listOf(pendingRow(7L))
        val seen = mutableListOf<CapturedText>()

        archive.replay { seen += it }

        assertEquals(1, seen.size)
        assertEquals(CapturedText(7L, "cmb.pb", "", "扣款50.00元", receivedAtMs), seen.single())
        coVerify(exactly = 1) { dao.updateStatus(7L, "auth_replayed", null) }
    }

    @Test
    @DisplayName("重放回调抛异常也标终态（不无限重试）")
    fun `重放失败仍标终态`() = runTest {
        coEvery { dao.findAuthPending(any()) } returns listOf(pendingRow(8L))

        archive.replay { throw RuntimeException("still failing") }

        coVerify(exactly = 1) { dao.updateStatus(8L, "auth_replayed", null) }
    }

    @Test
    @DisplayName("重放回调被取消 → 原样向上抛且不标终态（留给下次重登继续）")
    fun `重放取消不标终态`() = runTest {
        coEvery { dao.findAuthPending(any()) } returns listOf(pendingRow(9L))

        var thrown: Throwable? = null
        try {
            archive.replay { throw kotlinx.coroutines.CancellationException("cancelled") }
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        coVerify(exactly = 0) { dao.updateStatus(any(), any(), any()) }
    }

    @Test
    @DisplayName("没有待补偿记录时不回调")
    fun `无记录不回调`() = runTest {
        coEvery { dao.findAuthPending(any()) } returns emptyList()
        var called = false
        archive.replay { called = true }
        assertFalse(called)
    }

    @Test
    @DisplayName("重放只取保留时长（24h）内的记录")
    fun `重放只取24h内`() = runTest {
        val sinceSlot = slot<Long>()
        coEvery { dao.findAuthPending(capture(sinceSlot)) } returns emptyList()

        val before = System.currentTimeMillis()
        archive.replay { }
        val ttlMs = 24 * 60 * 60 * 1000L
        val since = sinceSlot.captured
        assertTrue(since <= before - ttlMs + 1_000, "since 应约为 24h 前，实际=$since")
        assertTrue(since >= before - ttlMs - 5_000)
    }
}
