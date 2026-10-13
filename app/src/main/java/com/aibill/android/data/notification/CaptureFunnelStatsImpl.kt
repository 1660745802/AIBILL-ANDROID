package com.aibill.android.data.notification

import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.SourceChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureFunnelStats] 的内存实现 + **轻量持久化**（P0-2）。
 *
 * 单个 `ConcurrentHashMap<String, AtomicLong>`：NLS / SMS / A11Y 三条协程会并发调用
 * （A11Y 的 `onAccessibilityEvent` 尤其频繁），计数不能丢；这里不引锁，避免给热路径加同步点。
 * stage 名自带渠道段（`received.<ch>` / `dropped.<ch>.<reason>`），所以不需要再按渠道分桶——
 * [Snapshot.reportCountsFor] 在读取时按名字归因。
 *
 * ## 持久化（为什么 + 怎么做）
 *
 * 原先纯内存 = 进程被杀即清零，而「AI 失败率」是「AI 失败不打扰用户」的唯一补偿，
 * 系统性偏低等于没人知道丢了账。现在：
 * - **热路径只改内存**（`record` 不碰 IO）；
 * - 变更后**合并落盘**（[FLUSH_DEBOUNCE_MS] 窗口，最多每 2s 写一次），进程被杀最多丢 ~2s；
 * - [reset]（上报 drain 后）清内存**并**清盘，且用 epoch 防止「reset 后落旧值」；
 * - 启动时读回未上报的计数（[FunnelStatsStore.load]），与并发 `record` **相加**（不覆盖）。
 *
 * 键名 / 窗口 / 上报语义**完全不变**：持久化只解决「重启丢计数」。
 */
@Singleton
class CaptureFunnelStatsImpl @Inject constructor(
    private val store: FunnelStatsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : CaptureFunnelStats {

    /** 单测 / 无持久化：不落盘（生产走上面的 `@Inject` 构造）。 */
    constructor() : this(NoopFunnelStatsStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** 串行化落盘 / 清除，避免「清完又被旧快照写回」。 */
    private val storeMutex = Mutex()

    /** `reset` 递增；在途的落盘若跨过 reset 则作废。 */
    private val epoch = AtomicLong(0L)

    /** 合并窗口内只挂一个落盘任务（`record` 是热路径，不能每次都建协程）。 */
    private val flushScheduled = AtomicBoolean(false)

    init {
        scope.launch {
            val persisted = runCatching { store.load() }.getOrNull().orEmpty()
            // 相加而非覆盖：load 与并发 record 竞争时，两者都不能丢。
            persisted.forEach { (stage, count) ->
                if (count > 0L) counters.computeIfAbsent(stage) { AtomicLong() }.addAndGet(count)
            }
        }
    }

    override fun record(stage: String) {
        counters.computeIfAbsent(stage) { AtomicLong() }.incrementAndGet()
        scheduleFlush()
    }

    override fun snapshot(): CaptureFunnelStats.Snapshot {
        val raw = counters.mapValues { it.value.get() }
        return CaptureFunnelStats.Snapshot(
            received = SourceChannel.entries.associateWith { channel ->
                raw[CaptureFunnelStats.receivedKey(channel)] ?: 0L
            },
            stages = raw.filterKeys { !it.startsWith(CaptureFunnelStats.RECEIVED_PREFIX) },
        )
    }

    override fun reset() {
        val newEpoch = epoch.incrementAndGet()
        counters.clear()
        scope.launch {
            storeMutex.withLock {
                // 只有在 reset 之后没有新的 reset 时才清盘（幂等，可重复清）
                if (epoch.get() == newEpoch) {
                    runCatching { store.clear() }
                }
            }
        }
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        val scheduledEpoch = epoch.get()
        scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            flushScheduled.set(false)
            storeMutex.withLock {
                // reset 已发生 → 这份快照是旧窗口的数据，不得写回
                if (epoch.get() != scheduledEpoch) return@withLock
                val counts = counters.mapValues { it.value.get() }
                if (counts.isEmpty()) return@withLock
                runCatching { store.save(counts) }
            }
        }
    }

    private companion object {
        /** 合并落盘窗口：最坏丢 ~2s 计数，换取热路径零 IO。 */
        const val FLUSH_DEBOUNCE_MS = 2_000L
    }
}
