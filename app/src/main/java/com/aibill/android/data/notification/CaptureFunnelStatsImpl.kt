package com.aibill.android.data.notification

import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.SourceChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureFunnelStats] 的内存实现。
 *
 * 单个 `ConcurrentHashMap<String, AtomicLong>`：NLS / SMS / A11Y 三条协程会并发调用
 * （A11Y 的 `onAccessibilityEvent` 尤其频繁），计数不能丢；这里不引锁，避免给热路径加同步点。
 * stage 名自带渠道段（`received.<ch>` / `dropped.<ch>.<reason>`），所以不需要再按渠道分桶——
 * [Snapshot.reportCountsFor] 在读取时按名字归因。
 *
 * 进程内计数，重启清零；[reset] 由 ⑤ 上报取走数据后调用。
 */
@Singleton
class CaptureFunnelStatsImpl @Inject constructor() : CaptureFunnelStats {

    private val counters = ConcurrentHashMap<String, AtomicLong>()

    override fun record(stage: String) {
        counters.computeIfAbsent(stage) { AtomicLong() }.incrementAndGet()
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
        counters.clear()
    }
}
