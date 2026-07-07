package com.aibill.android.service

import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 通知去重器（v3.2）
 *
 * 两层机制：
 * 1. 短延迟合并（5s）：同金额跨包名的通知合并文本后一起发 AI，信息更全
 * 2. 长窗口去重（60s）：合并处理后记入窗口，后续同金额直接丢弃
 *
 * 去重规则：
 * - 跨包名 + 同金额 + 窗口内 → 合并或丢弃
 * - 同包名 → 不去重（视为两笔真实交易）
 * - 金额为 null → 不去重
 */
@Singleton
class NotificationBuffer @Inject constructor() {

    data class BufferedItem(
        val packageName: String,
        val title: String,
        val fullText: String,
        /** 用简单正则快速提取的金额（分），仅用于去重比较 */
        val roughAmount: Int?,
        val receivedAt: Long = System.currentTimeMillis(),
    )

    /** 短延迟合并池：5s 内同金额的通知攒在一起 */
    private val pendingMerge = ConcurrentLinkedDeque<BufferedItem>()

    /** 60s 已处理记录（长窗口去重） */
    private val recentlyProcessed = ConcurrentLinkedDeque<ProcessedRecord>()

    private data class ProcessedRecord(
        val packageName: String,
        val amount: Int,
        val processedAt: Long,
    )

    companion object {
        const val MERGE_DELAY_MS = 5_000L   // 5s 短延迟合并
        const val DEDUP_WINDOW_MS = 60_000L // 60s 长窗口去重
    }

    /**
     * 60s 长窗口去重：只去"跨包名+同金额"的重复（同一笔支付的多渠道通知）。
     * 同包名来两条 = 真实的两笔交易，不去重。
     */
    fun isDuplicateInWindow(item: BufferedItem): Boolean {
        pruneProcessed()
        val amount = item.roughAmount ?: return false
        return recentlyProcessed.any { record ->
            record.packageName != item.packageName && // 必须跨包名
            record.amount == amount &&
            abs(item.receivedAt - record.processedAt) <= DEDUP_WINDOW_MS
        }
    }

    /**
     * 第二层：5s 短延迟合并。
     * 将通知加入合并池，返回是否是该金额的第一条（触发延迟处理）。
     */
    fun addToPendingMerge(item: BufferedItem): Boolean {
        val amount = item.roughAmount
        // 查是否已有同金额在等待合并
        val hasExisting = if (amount != null) {
            pendingMerge.any { it.roughAmount == amount && it.packageName != item.packageName }
        } else false

        pendingMerge.addLast(item)
        return !hasExisting // true = 第一条，需要启动 5s 延迟
    }

    /**
     * 5s 到期后取出同金额的所有通知，合并文本。
     * 返回合并后的 BufferedItem（fullText 包含所有渠道的文本）。
     */
    fun collectAndMerge(triggerAmount: Int?): BufferedItem? {
        if (triggerAmount == null) {
            // 无金额的直接取出第一条
            val item = pendingMerge.pollFirst() ?: return null
            return item
        }

        // 取出所有同金额的
        val matched = mutableListOf<BufferedItem>()
        val remaining = mutableListOf<BufferedItem>()
        for (item in pendingMerge) {
            if (item.roughAmount == triggerAmount) {
                matched.add(item)
            } else {
                remaining.add(item)
            }
        }
        pendingMerge.clear()
        remaining.forEach { pendingMerge.addLast(it) }

        if (matched.isEmpty()) return null

        // 合并文本：按信息丰富度排序，拼接
        val sorted = matched.sortedByDescending { it.fullText.length }
        val mergedText = sorted.joinToString("\n") { "[${it.title}] ${it.fullText}" }

        // 用信息最丰富的那条作为基础，替换 fullText 为合并后的
        return sorted.first().copy(fullText = mergedText)
    }

    /**
     * 标记某金额已处理完成（加入 60s 长窗口）
     */
    fun markProcessed(packageName: String, amount: Int) {
        recentlyProcessed.addLast(
            ProcessedRecord(packageName = packageName, amount = amount, processedAt = System.currentTimeMillis())
        )
    }

    private fun pruneProcessed() {
        val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS
        while (recentlyProcessed.peekFirst()?.let { it.processedAt < cutoff } == true) {
            recentlyProcessed.pollFirst()
        }
    }
}
