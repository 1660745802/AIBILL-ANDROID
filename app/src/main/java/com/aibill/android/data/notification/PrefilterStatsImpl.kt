package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.PrefilterStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PrefilterStats] 的内存实现。
 *
 * 用 `ConcurrentHashMap<BlockReason, AtomicLong>`：预筛本身被 NLS / SMS / A11Y 三条
 * 协程并发调用，计数不能丢；这里也不引锁，避免给热路径加同步点。
 */
@Singleton
class PrefilterStatsImpl @Inject constructor() : PrefilterStats {

    private val blocked = ConcurrentHashMap<BlockReason, AtomicLong>()
    private val passed = AtomicLong()

    override fun recordBlocked(reason: BlockReason) {
        blocked.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    override fun recordPassed() {
        passed.incrementAndGet()
    }

    override fun snapshot(): PrefilterStats.Snapshot = PrefilterStats.Snapshot(
        blocked = blocked.mapValues { it.value.get() },
        passed = passed.get(),
    )

    override fun reset() {
        blocked.clear()
        passed.set(0L)
    }
}
