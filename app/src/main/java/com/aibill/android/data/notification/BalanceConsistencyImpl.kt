package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BalanceConsistency
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BalanceConsistency] 的内存实现。
 *
 * ## 键的构造（决定「能不能比」）
 *
 * `card尾号 + 余额字段的语义前缀`：
 * - 语义前缀 = `balance_text` 去掉数字与分隔符后的残段（如「可用余额1234.56元」→「可用余额元」）。
 *   这样「可用余额」与「账户余额」天然是两把钥匙，不会被拿来互比（那会变成系统性误判）。
 * - 同一张卡的不同字段各记各的基线。
 *
 * ## 精度与容差
 *
 * 两侧都是整数分，理论上应精确相等；留 1 分容差只为兜住极端舍入，不掩盖真实差异。
 *
 * ## 时序
 *
 * 按**到达顺序**比较（不是交易时间）——通知/短信晚到几分钟很常见，用交易时间排序反而更乱。
 * 首条只记不判；对不上时**仍然更新基线**，这样「中间有一笔我们没记到的交易」之后
 * 能自动恢复自洽，不会一直报矛盾。
 */
@Singleton
class BalanceConsistencyImpl @Inject constructor() : BalanceConsistency {

    private val lastBalance = ConcurrentHashMap<String, Int>()

    override fun checkAndRecord(
        cardTail: String?,
        balanceText: String?,
        balanceAfterCents: Int?,
        amountCents: Int,
        type: String,
    ): BalanceConsistency.Verdict {
        val key = keyOf(cardTail, balanceText)
        val current = balanceAfterCents
        if (key == null || current == null || current < 0 || amountCents <= 0) {
            return BalanceConsistency.Verdict.UNKNOWN
        }
        // transfer 的方向不明（借/贷都可能），不比
        if (type !in EXPENSE_TYPES && type !in INCOME_TYPES) {
            return BalanceConsistency.Verdict.UNKNOWN
        }

        val previous = lastBalance.put(key, current)
        if (previous == null) return BalanceConsistency.Verdict.UNKNOWN

        val expected = if (type in EXPENSE_TYPES) previous - amountCents else previous + amountCents
        val diff = kotlin.math.abs(expected - current)
        return if (diff <= TOLERANCE_CENTS) {
            BalanceConsistency.Verdict.CONSISTENT
        } else {
            BalanceConsistency.Verdict.INCONSISTENT
        }
    }

    /**
     * `尾号|语义前缀`。任一为空则为 null（不比）——余额字段抽不到就不该猜。
     *
     * 前缀做归一：剔除数字、空白与常见分隔符，保留「可用余额」「账户余额」「余额元」这类词面，
     * 让同一字段在多次通知里稳定得到同一个键。
     */
    private fun keyOf(cardTail: String?, balanceText: String?): String? {
        val card = cardTail?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val text = balanceText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val semantic = text.filterNot { it.isDigit() || it.isWhitespace() || it in PURGE_CHARS }
            .take(SEMANTIC_PREFIX_LIMIT)
        if (semantic.isEmpty()) return null
        return "$card|$semantic"
    }

    private companion object {
        const val TOLERANCE_CENTS = 1
        const val SEMANTIC_PREFIX_LIMIT = 12
        const val PURGE_CHARS = ".,:：;；/￥¥+-=_()（）[]【】"

        val EXPENSE_TYPES = setOf("expense")
        val INCOME_TYPES = setOf("income")

        /** 仅供测试与诊断：清空基线（进程内状态，正常不需要重置） */
        @Suppress("unused")
        fun resetForTest() = Unit
    }
}
