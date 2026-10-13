package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationSink
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.util.AppLogger
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CaptureIngressImpl] 的接口级测试：C02 收口的「三 adapter 共同尾巴」。
 *
 * 钉住：Pass 才路由、Block 不路由、取消被重抛、其余异常只记日志、完成钩子会触发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureIngressTest {

    private val appLogger: AppLogger = mockk(relaxed = true)
    private val sink = FakeSink()

    private class FakeSink : NotificationSink {
        val received = mutableListOf<Capture>()
        override suspend fun process(capture: Capture) {
            received += capture
        }
    }

    private class FakePrefilter(private val verdict: suspend (Capture) -> Verdict) : NotificationPrefilter {
        override suspend fun evaluate(capture: Capture): Verdict = verdict(capture)
    }

    private fun capture() = Capture(
        channel = SourceChannel.NLS,
        packageName = "com.tencent.mm",
        title = "微信支付",
        fullText = "支付成功 ¥12.00",
        receivedAt = 0L,
    )

    private fun ingress(prefilter: NotificationPrefilter, scope: CoroutineScope) =
        CaptureIngressImpl(prefilter, sink, appLogger, scope)

    @Test
    fun `Pass 路由到 sink`() = runTest {
        val job = ingress(FakePrefilter { Verdict.Pass(it) }, backgroundScope).admit(capture())
        job.join()
        assertEquals(1, sink.received.size)
        assertEquals("支付成功 ¥12.00", sink.received.single().fullText)
    }

    @Test
    fun `Block 不路由，仅记日志`() = runTest {
        val prefilter = FakePrefilter { Verdict.Block(BlockReason.BLANK_TEXT, "空正文") }
        val job = ingress(prefilter, backgroundScope).admit(capture())
        job.join()
        assertTrue(sink.received.isEmpty())
        verify { appLogger.debug(any(), any()) }
    }

    @Test
    fun `CancellationException 被重抛（不吞）`() = runTest {
        val prefilter = FakePrefilter { throw CancellationException("cancelled") }
        val job = ingress(prefilter, backgroundScope).admit(capture())
        job.join()
        assertTrue(job.isCancelled, "取消必须原样传播，不能当成处理失败吞掉")
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun `其余异常只记日志、不外抛、不路由`() = runTest {
        val prefilter = FakePrefilter { throw IllegalStateException("boom") }
        val job = ingress(prefilter, backgroundScope).admit(capture())
        job.join()
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertTrue(sink.received.isEmpty())
        verify { appLogger.error(any(), any()) }
    }

    @Test
    fun `返回的 Job 完成钩子会触发（供 SMS 的 goAsync finish）`() = runTest {
        var finished = false
        val job = ingress(FakePrefilter { Verdict.Pass(it) }, backgroundScope).admit(capture())
        job.invokeOnCompletion { finished = true }
        job.join()
        assertTrue(finished)
    }
}
