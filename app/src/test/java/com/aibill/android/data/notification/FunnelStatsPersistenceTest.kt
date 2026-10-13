package com.aibill.android.data.notification

import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.SourceChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 漏斗计数持久化（P0-2）：进程重启不丢「还没上报」的计数。
 *
 * 热路径只改内存，落盘是 2s 合并窗口的旁路；`reset`（上报 drain 后）清内存 + 清盘，
 * 且不得把 reset 之前捕获的旧快照写回。
 *
 * 用 `UnconfinedTestDispatcher` 注入 scope：无延迟的 load/save/clear 立即执行，
 * `delay(2s)` 的落盘仍由虚拟时钟（`advanceTimeBy`）驱动，测试确定性可断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FunnelStatsPersistenceTest {

    private class FakeStore(@Volatile var counts: Map<String, Long> = emptyMap()) : FunnelStatsStore {
        override suspend fun load(): Map<String, Long> = counts
        override suspend fun save(counts: Map<String, Long>) {
            this.counts = counts
        }

        override suspend fun clear() {
            counts = emptyMap()
        }
    }

    private val aiFailed = CaptureFunnelStats.unparsedFailedStage(SourceChannel.NLS)

    private fun TestScope.newStats(store: FunnelStatsStore) =
        CaptureFunnelStatsImpl(store, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    @DisplayName("启动时读回未上报计数（跨进程重启不丢）")
    fun restoresPersistedCounts() = runTest {
        val stats = newStats(FakeStore(mapOf(aiFailed to 3L)))

        assertEquals(3L, stats.snapshot().unparsedAiFailedTotal)
    }

    @Test
    @DisplayName("record 后合并落盘，且与已恢复的计数相加（不覆盖）")
    fun flushesCoalescedAndAddsToRestored() = runTest {
        val store = FakeStore(mapOf(aiFailed to 2L))
        val stats = newStats(store)
        assertEquals(2L, stats.snapshot().unparsedAiFailedTotal)

        repeat(5) { stats.record(aiFailed) }
        advanceTimeBy(2_500)
        runCurrent()

        assertEquals(7L, stats.snapshot().unparsedAiFailedTotal)
        assertEquals(7L, store.counts[aiFailed])
    }

    @Test
    @DisplayName("reset 清内存 + 清盘")
    fun resetClearsMemoryAndStore() = runTest {
        val store = FakeStore()
        val stats = newStats(store)

        stats.record(aiFailed)
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(store.counts.isNotEmpty())

        stats.reset()

        assertEquals(0L, stats.snapshot().unparsedTotal)
        assertTrue(store.counts.isEmpty(), "reset 必须清盘，实际=${store.counts}")
    }

    @Test
    @DisplayName("reset 前捕获的在途快照不得写回（epoch 护栏）")
    fun inFlightFlushAfterResetIsDropped() = runTest {
        val store = FakeStore()
        val stats = newStats(store)

        stats.record(aiFailed)   // 排一个 2s 后的落盘
        stats.reset()            // 立刻 drain，epoch 前进并清盘
        advanceTimeBy(2_500)
        runCurrent()

        assertTrue(store.counts.isEmpty(), "旧窗口的计数被写回了：${store.counts}")
    }

    @Test
    @DisplayName("reset 之后的新计数照常落盘，旧计数器不复现")
    fun newCountsAfterResetPersist() = runTest {
        val store = FakeStore()
        val stats = newStats(store)

        stats.record(CaptureFunnelStats.RECEIVED_NLS)
        advanceTimeBy(2_500)
        runCurrent()
        stats.reset()

        stats.record(CaptureFunnelStats.RECEIVED_SMS)
        advanceTimeBy(2_500)
        runCurrent()

        assertEquals(1L, store.counts[CaptureFunnelStats.RECEIVED_SMS])
        assertNull(store.counts[CaptureFunnelStats.RECEIVED_NLS])
    }
}
