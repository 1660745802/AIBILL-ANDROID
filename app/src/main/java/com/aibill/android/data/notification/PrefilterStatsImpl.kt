package com.aibill.android.data.notification

import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.SourceChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PrefilterStats] 的内存实现。
 *
 * 用 `ConcurrentHashMap<SourceChannel, ConcurrentHashMap<String, AtomicLong>>`：预筛本身被
 * NLS / SMS / A11Y 三条协程并发调用，计数不能丢；这里也不引锁，避免给热路径加同步点。
 * 按渠道分桶是为了 ⑤ 的 `/api/ai/filter-report`——契约 §4 的 `channel` 要求按渠道拆窗。
 */
@Singleton
class PrefilterStatsImpl @Inject constructor() : PrefilterStats {

    private val blocked = ConcurrentHashMap<SourceChannel, ConcurrentHashMap<String, AtomicLong>>()
    private val unusableRules = ConcurrentHashMap<SourceChannel, ConcurrentHashMap<String, AtomicLong>>()
    private val passed = ConcurrentHashMap<SourceChannel, AtomicLong>()

    /** 当前窗口起点；[drain] / [reset] 时推进。 */
    private val windowStartMs = AtomicLong(System.currentTimeMillis())

    override fun recordBlocked(channel: SourceChannel, reason: String) {
        counters(blocked, channel).computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    override fun recordPassed(channel: SourceChannel) {
        passed.computeIfAbsent(channel) { AtomicLong() }.incrementAndGet()
    }

    override fun recordUnusablePrecheckRule() {
        recordDiagnostic(PrefilterStats.PRECHECK_UNKNOWN_EXPR)
    }

    override fun recordUnknownPrecheckIds() {
        recordDiagnostic(PrefilterStats.PRECHECK_UNKNOWN_IDS)
    }

    /** 规则级诊断没有「渠道」语义；挂在 NLS 桶（最常触发的渠道）以免每个渠道各报一份。 */
    private fun recordDiagnostic(key: String) {
        counters(unusableRules, SourceChannel.NLS)
            .computeIfAbsent(key) { AtomicLong() }
            .incrementAndGet()
    }

    override fun snapshot(): PrefilterStats.Snapshot =
        buildSnapshot(windowStartMs.get(), System.currentTimeMillis())

    override fun drain(now: Long): PrefilterStats.Snapshot {
        val start = windowStartMs.getAndSet(now)
        return buildSnapshot(start, now).also { clearCounters() }
    }

    override fun reset() {
        clearCounters()
        windowStartMs.set(System.currentTimeMillis())
    }

    private fun buildSnapshot(start: Long, end: Long): PrefilterStats.Snapshot {
        val channels = LinkedHashSet<SourceChannel>()
        channels += blocked.keys
        channels += unusableRules.keys
        channels += passed.keys
        val perChannel = channels.associateWith { channel ->
            PrefilterStats.Snapshot.ChannelCounts(
                blocked = blocked[channel].orEmptyMap(),
                unusableRules = unusableRules[channel].orEmptyMap(),
                passed = passed[channel]?.get() ?: 0L,
            )
        }
        return PrefilterStats.Snapshot(windowStart = start, windowEnd = end, channels = perChannel)
    }

    private fun clearCounters() {
        blocked.clear()
        unusableRules.clear()
        passed.clear()
    }

    private fun counters(
        target: ConcurrentHashMap<SourceChannel, ConcurrentHashMap<String, AtomicLong>>,
        channel: SourceChannel,
    ): ConcurrentHashMap<String, AtomicLong> = target.computeIfAbsent(channel) { ConcurrentHashMap() }

    private fun ConcurrentHashMap<String, AtomicLong>?.orEmptyMap(): Map<String, Long> =
        this?.mapValues { it.value.get() }.orEmpty()
}
