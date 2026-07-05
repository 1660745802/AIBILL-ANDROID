package com.aibill.android.util

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知链关联识别器（三级去重优先级）
 *
 * 同一笔交易可能产生多条通知（如微信通知 + 银行短信），
 * 通过订单号精确匹配和金额+时间窗口模糊匹配进行去重。
 *
 * 去重优先级：
 * 1. 订单号/商户单号相同 → 精确匹配（最可靠）
 * 2. 跨包名同金额 + 10 秒窗口 → 覆盖"先微信后银行"
 * 3. 同金额 + 5 分钟窗口 → 兜底（原有逻辑）
 */
@Singleton
class NotificationCorrelator @Inject constructor() {

    data class NotificationRecord(
        val packageName: String,
        val amount: Int, // 单位：分
        val description: String?,
        val orderId: String?,
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed class CorrelationResult {
        /** 该通知应被跳过（已有更优记录） */
        data object Skip : CorrelationResult()

        /** 该通知可以正常处理 */
        data class Proceed(val record: NotificationRecord) : CorrelationResult()
    }

    companion object {
        private const val CROSS_PACKAGE_WINDOW_MS = 10_000L // 10 秒（跨包名短窗口）
        private const val SAME_AMOUNT_WINDOW_MS = 5 * 60 * 1000L // 5 分钟（兜底窗口）
    }

    private val window = ConcurrentLinkedDeque<NotificationRecord>()

    /**
     * 检查新通知是否与窗口内记录重复
     *
     * @param orderId 订单号（来自 NotificationParser.ParseResult.orderId）
     * @return CorrelationResult 指示该通知应被跳过还是继续处理
     */
    fun check(
        packageName: String,
        amount: Int,
        description: String?,
        orderId: String? = null,
    ): CorrelationResult {
        val now = System.currentTimeMillis()
        pruneExpired(now)

        val newRecord = NotificationRecord(
            packageName = packageName,
            amount = amount,
            description = description,
            orderId = orderId,
            timestamp = now
        )

        // === 第 1 优先级：订单号精确匹配 ===
        if (!orderId.isNullOrBlank()) {
            val byOrderId = window.firstOrNull {
                !it.orderId.isNullOrBlank() && it.orderId == orderId
            }
            if (byOrderId != null) {
                Timber.d("通知关联[P1-订单号]: 跳过重复 orderId=$orderId")
                return resolveConflict(byOrderId, newRecord)
            }
        }

        // === 第 2 优先级：跨包名同金额 + 10 秒窗口 ===
        val crossPackage = window.firstOrNull {
            it.amount == amount &&
            it.packageName != packageName &&
            (now - it.timestamp) <= CROSS_PACKAGE_WINDOW_MS
        }
        if (crossPackage != null) {
            Timber.d("通知关联[P2-跨包名10s]: 跳过重复 amount=${amount}分, ${crossPackage.packageName}→$packageName")
            return resolveConflict(crossPackage, newRecord)
        }

        // === 第 3 优先级：同金额 + 5 分钟窗口（兜底） ===
        val sameAmount = window.firstOrNull {
            it.amount == amount && (now - it.timestamp) <= SAME_AMOUNT_WINDOW_MS
        }
        if (sameAmount != null) {
            Timber.d("通知关联[P3-同金额5min]: 跳过重复 amount=${amount}分")
            return resolveConflict(sameAmount, newRecord)
        }

        // 无重复，加入窗口
        window.addLast(newRecord)
        return CorrelationResult.Proceed(newRecord)
    }

    /**
     * 冲突解决：保留信息更丰富的记录。
     * 新记录更优 → 替换旧的，返回 Proceed
     * 旧记录更优 → 跳过新的，返回 Skip
     */
    private fun resolveConflict(
        existing: NotificationRecord,
        newRecord: NotificationRecord,
    ): CorrelationResult {
        val existingScore = infoScore(existing)
        val newScore = infoScore(newRecord)

        return if (newScore > existingScore) {
            window.remove(existing)
            window.addLast(newRecord)
            CorrelationResult.Proceed(newRecord)
        } else {
            CorrelationResult.Skip
        }
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - SAME_AMOUNT_WINDOW_MS
        while (window.peekFirst()?.let { it.timestamp < cutoff } == true) {
            window.pollFirst()
        }
    }

    /**
     * 信息完整度评分：描述越丰富 + 有订单号 → 分数越高
     */
    private fun infoScore(record: NotificationRecord): Int {
        var score = 0
        if (!record.description.isNullOrBlank()) {
            score += record.description.length.coerceAtMost(50)
        }
        if (!record.orderId.isNullOrBlank()) {
            score += 20 // 有订单号的通知更有价值
        }
        // 微信/支付宝通知通常比短信信息更丰富
        if (record.packageName.contains("tencent") || record.packageName.contains("Alipay")) {
            score += 10
        }
        return score
    }
}
