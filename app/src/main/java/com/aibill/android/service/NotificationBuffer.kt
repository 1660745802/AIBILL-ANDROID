package com.aibill.android.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 通知缓冲池。
 *
 * 设计目标：收齐同一笔支付产生的多条通知（微信+银行+短信），去重后只保留一条交给 AI。
 *
 * 触发条件（任一满足即 flush）：
 * - 最早通知已等 60s（超时）
 * - 池中满 5 条（批量效率）
 *
 * 去重规则：
 * - 跨包名 + 同金额 + 60s 内 → 合并（保留信息最丰富的那条）
 * - 同包名两条 → 不合并（视为两笔真实交易）
 * - 金额为 null → 不参与金额去重（全部保留，交给 AI）
 */
@Singleton
class NotificationBuffer @Inject constructor() {

    data class BufferedItem(
        val packageName: String,
        val title: String,
        val fullText: String,
        /** 用简单正则快速提取的金额（分），仅用于去重比较，不作为最终结果 */
        val roughAmount: Int?,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    private val pool = ConcurrentLinkedDeque<BufferedItem>()
    private var flushJob: Job? = null

    companion object {
        const val BUFFER_DELAY_MS = 60_000L  // 60s 缓冲（覆盖银行短信延迟 30-60s）
        const val MAX_BATCH_SIZE = 5
    }

    /**
     * 通知入池。到期或满批时通过 onFlush 回调交出去重后的列表。
     */
    fun enqueue(
        item: BufferedItem,
        scope: CoroutineScope,
        onFlush: suspend (List<BufferedItem>) -> Unit,
    ) {
        pool.addLast(item)

        // 满 5 条 → 立即 flush
        if (pool.size >= MAX_BATCH_SIZE) {
            flush(scope, onFlush)
            return
        }

        // 否则延迟 60s flush（已有延迟任务就不重复创建）
        if (flushJob == null || flushJob?.isActive != true) {
            flushJob = scope.launch {
                delay(BUFFER_DELAY_MS)
                flush(scope, onFlush)
            }
        }
    }

    /**
     * 主动 flush（如用户打开 App 时立即处理）
     */
    fun flushNow(scope: CoroutineScope, onFlush: suspend (List<BufferedItem>) -> Unit) {
        flush(scope, onFlush)
    }

    private fun flush(scope: CoroutineScope, onFlush: suspend (List<BufferedItem>) -> Unit) {
        flushJob?.cancel()
        flushJob = null

        val batch = mutableListOf<BufferedItem>()
        while (pool.isNotEmpty()) {
            pool.pollFirst()?.let { batch.add(it) }
        }
        if (batch.isEmpty()) return

        val deduplicated = deduplicate(batch)
        scope.launch { onFlush(deduplicated) }
    }

    /**
     * 去重：跨包名 + 同金额 + 60s 内 → 合并为一条（保留信息最丰富的）
     */
    internal fun deduplicate(batch: List<BufferedItem>): List<BufferedItem> {
        val result = mutableListOf<BufferedItem>()
        val consumed = mutableSetOf<Int>()

        for (i in batch.indices) {
            if (i in consumed) continue
            var best = batch[i]
            for (j in i + 1 until batch.size) {
                if (j in consumed) continue
                if (shouldMerge(best, batch[j])) {
                    consumed.add(j)
                    if (infoScore(batch[j]) > infoScore(best)) {
                        best = batch[j]
                    }
                }
            }
            result.add(best)
        }
        return result
    }

    private fun shouldMerge(a: BufferedItem, b: BufferedItem): Boolean {
        // 同 App 不合并（即使同金额，视为两笔真实交易）
        if (a.packageName == b.packageName) return false
        // 任一无金额 → 无法判断，不合并
        if (a.roughAmount == null || b.roughAmount == null) return false
        // 金额不同 → 不合并
        if (a.roughAmount != b.roughAmount) return false
        // 时间超过 60s → 不合并
        if (abs(a.receivedAt - b.receivedAt) > BUFFER_DELAY_MS) return false
        return true
    }

    /**
     * 信息丰富度评分。优先保留内容多的、来自微信/支付宝的（它们有商家名）。
     */
    private fun infoScore(item: BufferedItem): Int {
        var score = item.fullText.length.coerceAtMost(100)
        // 微信/支付宝通知通常有商家名，信息最丰富
        if (item.packageName.contains("tencent")) score += 20
        if (item.packageName.contains("Alipay")) score += 15
        return score
    }
}
