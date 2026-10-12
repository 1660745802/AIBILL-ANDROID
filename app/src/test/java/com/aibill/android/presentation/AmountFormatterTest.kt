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

    @Test
    @DisplayName("用户输入的元文本 → 分：逐位整数换算，不经过 Double")
    fun yuanTextToFenIsExact() {
        // 0.29 * 100 == 28.999… → 截断成 28（旧实现丢 1 分）
        assertEquals(29, AmountFormatter.yuanTextToFen("0.29"))
        assertEquals(28, AmountFormatter.yuanTextToFen("0.28"))
        assertEquals(1, AmountFormatter.yuanTextToFen("0.01"))
        assertEquals(10, AmountFormatter.yuanTextToFen("0.1"))
        assertEquals(50, AmountFormatter.yuanTextToFen("0.5"))
        assertEquals(3250, AmountFormatter.yuanTextToFen("32.5"))
        assertEquals(3250, AmountFormatter.yuanTextToFen("32.50"))
        assertEquals(100, AmountFormatter.yuanTextToFen("1"))
        assertEquals(0, AmountFormatter.yuanTextToFen(""))
        assertEquals(0, AmountFormatter.yuanTextToFen("."))
        assertEquals(0, AmountFormatter.yuanTextToFen("abc"))
        // 负余额（信用卡欠款）保留符号
        assertEquals(-50, AmountFormatter.yuanTextToFen("-0.50"))
        // 千分位/空格不该出现在输入里，但出现了也不能崩，按数字前缀解析
        assertEquals(123456, AmountFormatter.yuanTextToFen(" 1234.56 "))
        // 超上限收敛到 Int 边界，且不溢出成负数
        assertEquals(Int.MAX_VALUE, AmountFormatter.yuanTextToFen("99999999999999999999"))
        assertEquals(Int.MIN_VALUE, AmountFormatter.yuanTextToFen("-99999999999999999999"))
        // 边界内最大可表示值
        assertEquals(2147483647, AmountFormatter.yuanTextToFen("21474836.47"))
        // 紧邻的溢出侧：Long 相加后收敛，不得回绕成负数（正输入不能变负）
        assertEquals(Int.MAX_VALUE, AmountFormatter.yuanTextToFen("21474836.48"))
        assertEquals(Int.MAX_VALUE, AmountFormatter.yuanTextToFen("21474836.99"))
        assertEquals(Int.MIN_VALUE, AmountFormatter.yuanTextToFen("-21474836.99"))
    }
}
