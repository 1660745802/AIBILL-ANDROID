package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BalanceConsistency.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 余额自洽校验（借鉴第三方方案里唯一值得抄的一条：用手里的余额自证交易）。
 *
 * 钉住四类行为：
 * 1. 同卡同字段 → 支出/收入都按各自方向自洽；
 * 2. **不同余额字段永不互比**（「可用余额」vs「账户余额」→ UNKNOWN，否则是系统性误判）；
 * 3. 判不出来的一律 UNKNOWN 且**不影响入账决策**（缺字段 / transfer / 首次见到该卡）；
 * 4. 矛盾时**仍然更新基线**：中间漏记一笔之后能自动恢复自洽，不会一直报矛盾。
 */
class BalanceConsistencyTest {

    private val impl = BalanceConsistencyImpl()

    private fun check(
        card: String? = "1234",
        text: String? = "可用余额1000.00元",
        balance: Int? = 100_000,
        amount: Int = 3_250,
        type: String = "expense",
    ) = impl.checkAndRecord(card, text, balance, amount, type)

    @Test
    @DisplayName("首次见到该卡 → UNKNOWN（只记基线，不判）")
    fun firstSightingIsUnknown() {
        assertEquals(Verdict.UNKNOWN, check())
    }

    @Test
    @DisplayName("支出自洽：上条余额 − 本笔金额 = 本条余额")
    fun expenseConsistent() {
        check(balance = 100_000)                       // 基线 1000.00
        assertEquals(Verdict.CONSISTENT, check(balance = 96_750, amount = 3_250))
    }

    @Test
    @DisplayName("收入自洽：上条余额 + 本笔金额 = 本条余额")
    fun incomeConsistent() {
        check(balance = 100_000, type = "income")
        assertEquals(
            Verdict.CONSISTENT,
            check(balance = 120_000, amount = 20_000, type = "income"),
        )
    }

    @Test
    @DisplayName("方向反了 → INCONSISTENT（金额抽错/方向判错的强信号）")
    fun directionMismatchIsInconsistent() {
        check(balance = 100_000, type = "income")
        // 记成收入却让余额减少
        assertEquals(Verdict.INCONSISTENT, check(balance = 96_750, amount = 3_250, type = "income"))
    }

    @Test
    @DisplayName("不同余额字段永不互比（可用余额 vs 账户余额 → UNKNOWN）")
    fun differentFieldsNeverCompared() {
        check(text = "可用余额1000.00元", balance = 100_000)
        // 换字段：即使数值「对不上」也只能是 UNKNOWN
        assertEquals(
            Verdict.UNKNOWN,
            check(text = "账户余额967.50元", balance = 96_750, amount = 3_250),
        )
    }

    @Test
    @DisplayName("缺少卡号 / 余额 / 文本 或 transfer → 一律 UNKNOWN（不影响决策）")
    fun insufficientInputIsUnknown() {
        assertEquals(Verdict.UNKNOWN, check(card = null))
        assertEquals(Verdict.UNKNOWN, check(text = null))
        assertEquals(Verdict.UNKNOWN, check(balance = null))
        assertEquals(Verdict.UNKNOWN, check(type = "transfer"))
        // 首次 + transfer：比不出来也不该判矛盾
        check(balance = 100_000)
        assertEquals(Verdict.UNKNOWN, check(balance = 1, amount = 1, type = "transfer"))
    }

    @Test
    @DisplayName("1 分容差：精确值允许 ±1 分，真实差异不被掩盖")
    fun toleranceIsOneCent() {
        // 每断言用独立卡号：checkAndRecord 会把基线推进到本条，同一卡连续断言会互相干扰
        check(card = "A", balance = 100_000)
        assertEquals(Verdict.CONSISTENT, check(card = "A", balance = 96_751, amount = 3_250)) // 差 1 分

        check(card = "B", balance = 100_000)
        assertEquals(Verdict.CONSISTENT, check(card = "B", balance = 96_749, amount = 3_250)) // 差 1 分

        check(card = "C", balance = 100_000)
        assertEquals(Verdict.INCONSISTENT, check(card = "C", balance = 96_752, amount = 3_250)) // 差 2 分
    }

    @Test
    @DisplayName("矛盾后仍更新基线 → 中间漏记一笔后能自动恢复自洽")
    fun updatesBaselineEvenWhenInconsistent() {
        check(balance = 100_000)
        // 中间有一笔我们没记到的交易（余额直接跳到 90_000）
        assertEquals(Verdict.INCONSISTENT, check(balance = 90_000, amount = 3_250))
        // 以 90_000 为新基线 → 下一条恢复正常自洽
        assertEquals(Verdict.CONSISTENT, check(balance = 86_750, amount = 3_250))
    }

    @Test
    @DisplayName("同卡不同字段各记各的基线（不会串味）")
    fun perFieldBaselines() {
        check(text = "可用余额1000.00元", balance = 100_000)
        check(text = "账户余额2000.00元", balance = 200_000)
        assertEquals(Verdict.CONSISTENT, check(text = "可用余额967.50元", balance = 96_750, amount = 3_250))
        assertEquals(Verdict.CONSISTENT, check(text = "账户余额1967.50元", balance = 196_750, amount = 3_250))
    }
}
