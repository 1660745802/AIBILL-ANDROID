package com.aibill.android.data.notification

import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiCircuitBreaker] 的状态机实现：每来源一份「连续失败次数 + 静音到期时刻」。
 *
 * 状态是进程级的（跨通知存活），因此本类是 `@Singleton`。
 */
@Singleton
class AiCircuitBreakerImpl @Inject constructor(
    private val appLogger: AppLogger,
) : AiCircuitBreaker {

    private data class Breaker(
        val failures: Int,
        val lastFailureAt: Long,
        val mutedUntil: Long,
    )

    private val breakers = ConcurrentHashMap<String, Breaker>()

    override fun isMuted(source: String, now: Long): Boolean {
        val breaker = breakers[source] ?: return false
        if (now < breaker.mutedUntil) return true
        // 熔断已到期：清零后重新计数，避免一次抖动就永久静音
        if (breaker.mutedUntil > 0L) {
            breakers.remove(source, breaker)
        }
        return false
    }

    override fun recordOutcome(source: String, success: Boolean, now: Long) {
        if (success) {
            breakers.remove(source)
            return
        }
        val previous = breakers[source]
        val failures = (previous?.failures ?: 0) + 1
        val mutedUntil = if (failures >= FAIL_THRESHOLD) now + COOLDOWN_MS else 0L
        breakers[source] = Breaker(failures, now, mutedUntil)
        if (mutedUntil > 0L) {
            appLogger.warn(
                "NLS",
                "AI 熔断开启: pkg=$source 连续失败 $failures 次，静音 ${COOLDOWN_MS / 1000}s"
            )
        }
        // 顺手清理长时间无失败记录的条目，防 map 无限增长
        if (breakers.size > MAX_ENTRIES) {
            breakers.entries.removeIf { now - it.value.lastFailureAt > COOLDOWN_MS * 4 }
        }
    }

    private companion object {
        /** 连续失败多少次后对该来源静音 */
        const val FAIL_THRESHOLD = 3

        /** 熔断静音时长 */
        const val COOLDOWN_MS = 5 * 60_000L

        /** 熔断表条数上限，超过后触发过期清理 */
        const val MAX_ENTRIES = 64
    }
}
