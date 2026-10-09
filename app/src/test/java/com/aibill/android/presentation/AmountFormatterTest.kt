package com.aibill.android.presentation

import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.components.AmountFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 金额格式化回归测试。
 *
 * 这组用例锁住两个曾经真实存在过的 bug：
 * 1. 负号被输出在 `¥` 后面 → `¥-32.50`
 * 2. Java 整数除法截断 → `-50` 分被算成 `¥0.50`（符号和角分全丢）
 *
 * 另外锁住一条业务规则：**符号跟业务类型走，不跟数值正负走**。
 * 一笔支出不管金额字段是正是负，显示都必须是 `-`；收入恒 `+`。
 */
class AmountFormatterTest {

    @Test
    @DisplayName("负号在 ¥ 之前，且不丢角分")
    fun negativeSignPlacement() {
        assertEquals("-¥32.50", AmountFormatter.toYuanDisplay(-3250))
        assertEquals("¥32.50", AmountFormatter.toYuanDisplay(3250))
        // -50 分：旧实现 `cents / 100` 截断成 0 → "¥0.50"
        assertEquals("-¥0.50", AmountFormatter.toYuanDisplay(-50))
        assertEquals("¥0.50", AmountFormatter.toYuanDisplay(50))
        assertEquals("-¥1.00", AmountFormatter.toYuanDisplay(-100))
    }

    @Test
    @DisplayName("符号跟业务类型走，不跟数值正负走 —— 不会出现 -¥-32.50")
    fun signFollowsSemanticsNotValue() {
        // 正数支出 → -
        assertEquals("-¥32.50", AmountFormatter.toSignedDisplay(3250, TransactionType.EXPENSE))
        // 负数支出（异常数据）→ 仍然是 -，不能出现双符号
        assertEquals("-¥32.50", AmountFormatter.toSignedDisplay(-3250, TransactionType.EXPENSE))
        // 收入恒 +
        assertEquals("+¥32.50", AmountFormatter.toSignedDisplay(3250, TransactionType.INCOME))
        assertEquals("+¥32.50", AmountFormatter.toSignedDisplay(-3250, TransactionType.INCOME))
        // 转账不带符号
        assertEquals("¥32.50", AmountFormatter.toSignedDisplay(-3250, TransactionType.TRANSFER))
    }

    @Test
    @DisplayName("紧凑显示与轴标签也保留负号")
    fun compactAndAxisKeepSign() {
        assertEquals("-¥1,234", AmountFormatter.toCompactDisplay(-123456))
        assertEquals("¥1,234", AmountFormatter.toCompactDisplay(123456))
        assertEquals("¥1,234,567.89", AmountFormatter.toYuanDisplay(123456789))
    }

    @Test
    @DisplayName("轴标签的「万」阈值必须是 ¥10,000，不能是 ¥1,000")
    fun axisLabelWanThreshold() {
        // ¥1,234 —— 低于一万，必须原样显示；旧实现会输出无意义的 "0.1万"
        assertEquals("1,234", AmountFormatter.toAxisLabel(123456))
        assertEquals("-1,234", AmountFormatter.toAxisLabel(-123456))
        // ¥12,345 起才折算
        assertEquals("1.2万", AmountFormatter.toAxisLabel(1234567))
        assertEquals("-1.2万", AmountFormatter.toAxisLabel(-1234567))
        assertEquals("1234.6万", AmountFormatter.toAxisLabel(1234567890))
    }

    @Test
    @DisplayName("千分位分组正确")
    fun thousandsSeparator() {
        assertEquals("¥0.00", AmountFormatter.toYuanDisplay(0))
        assertEquals("¥9.99", AmountFormatter.toYuanDisplay(999))
        assertEquals("¥1,000.00", AmountFormatter.toYuanDisplay(100000))
        assertEquals("¥1,000,000.00", AmountFormatter.toYuanDisplay(100000000))
    }
}
