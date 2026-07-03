package com.aibill.android.util

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知链关联识别器
 *
 * 用户角度：美团外卖下单后可能收到「美团送达」+「微信支付」两条通知，
 * 不应重复记账。通过滑动窗口检测相同金额的通知进行去重/合并。
 */
@Singleton
class NotificationCorrelator @Inject constructor() {

    data class NotificationRecord(
        val packageName: String,
        val amount: Int, // 单位：分
        val description: String?,
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed class CorrelationResult {
        /** 该通知应被跳过（已有更优记录） */
        data object Skip : CorrelationResult()

        /** 该通知可以正常处理 */
        data class Proceed(val record: NotificationRecord) : CorrelationResult()
    }

    companion object {
        private const val WINDOW_MS = 5 * 60 * 1000L // 5 分钟窗口
    }

    private val window = ConcurrentLinkedDeque<NotificationRecord>()

    /**
     * 检查新通知是否与窗口内记录重复
     *
     * @return CorrelationResult 指示该通知应被跳过还是继续处理
     */
    fun check(packageName: String, amount: Int, description: String?): CorrelationResult {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        val newRecord = NotificationRecord(
            packageName = packageName,
            amount = amount,
            description = description,
            timestamp = now
        )

        // 在窗口内查找相同金额的记录
        val existing = window.firstOrNull { it.amount == amount }

        if (existing != null) {
            // 找到重复：比较哪条信息更全
            val existingScore = infoScore(existing)
            val newScore = infoScore(newRecord)

            return if (newScore > existingScore) {
                // 新记录更优，替换旧记录
                window.remove(existing)
                window.addLast(newRecord)
                Timber.d("通知关联: 新记录更优，替换旧记录 (金额=${amount}分)")
                CorrelationResult.Proceed(newRecord)
            } else {
                // 旧记录更优，跳过新通知
                Timber.d("通知关联: 跳过重复通知 (金额=${amount}分, 包名=$packageName)")
                CorrelationResult.Skip
            }
        }

        // 无重复，加入窗口
        window.addLast(newRecord)
        return CorrelationResult.Proceed(newRecord)
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - WINDOW_MS
        while (window.peekFirst()?.let { it.timestamp < cutoff } == true) {
            window.pollFirst()
        }
    }

    /**
     * 信息完整度评分：描述越丰富分数越高
     */
    private fun infoScore(record: NotificationRecord): Int {
        var score = 0
        if (!record.description.isNullOrBlank()) {
            score += record.description.length.coerceAtMost(50)
        }
        // 微信/支付宝通知通常比短信信息更丰富
        if (record.packageName.contains("tencent") || record.packageName.contains("Alipay")) {
            score += 10
        }
        return score
    }
}
