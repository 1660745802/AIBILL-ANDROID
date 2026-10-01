package com.aibill.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 金额工具类测试。
 *
 * CONTRIBUTING §2.3 要求工具类覆盖率 100%，本类此前 0 覆盖，
 * 而它同时是 eb492b5（金额输入小数位补 00）和 P1-4（输入溢出静默截断）的核心组件。
 */
class AmountUtilsTest {

    @Nested
    @DisplayName("fenToYuan - 分转元")
    inner class FenToYuan {

        @Test
        fun `0 分 → 零点零零`() = assertEquals("0.00", AmountUtils.fenToYuan(0))

        @Test
        fun `3250 分 → 三十二点五零`() = assertEquals("32.50", AmountUtils.fenToYuan(3250))

        @Test
        fun `5 分 → 零点零五（补零）`() = assertEquals("0.05", AmountUtils.fenToYuan(5))

        @Test
        fun `100000 分 → 一千`() = assertEquals("1000.00", AmountUtils.fenToYuan(100000))

        @Test
        fun `负数分 → 负数元（退款场景）`() = assertEquals("-12.30", AmountUtils.fenToYuan(-1230))
    }

    @Nested
    @DisplayName("parseExpression - 计算器表达式求值（结果单位=分）")
    inner class ParseExpression {

        @Test
        fun `单个数字：输入视为元，输出分`() {
            assertEquals(1000, AmountUtils.parseExpression("10"))
            assertEquals(1050, AmountUtils.parseExpression("10.5"))
        }

        @Test
        fun `两位小数精确到分`() {
            assertEquals(3250, AmountUtils.parseExpression("32.50"))
            assertEquals(1, AmountUtils.parseExpression("0.01"))
        }

        @Test
        fun `加法`() = assertEquals(1370, AmountUtils.parseExpression("10.5+3.2"))

        @Test
        fun `减法`() = assertEquals(730, AmountUtils.parseExpression("10.5-3.2"))

        @Test
        fun `乘法优先级高于加法（10 加 3 乘 2 等于 16 而非 26）`() {
            assertEquals(1600, AmountUtils.parseExpression("10+3*2"))
        }

        @Test
        fun `除法与乘法同优先级、从左到右（10 乘 2 除 4 等于 5）`() {
            // 20/4 = 5 元 = 500 分
            assertEquals(500, AmountUtils.parseExpression("10*2/4"))
        }

        @Test
        fun `连续运算符：前一个结果与后一个操作数继续计算`() {
            // 2+3*4-1 = 13
            assertEquals(1300, AmountUtils.parseExpression("2+3*4-1"))
        }

        @Test
        fun `空格被忽略`() = assertEquals(1500, AmountUtils.parseExpression("10 + 5"))

        @Test
        fun `首字符负号：-5 元 = -500 分`() {
            assertEquals(-500, AmountUtils.parseExpression("-5"))
        }

        @Test
        fun `运算符后负号：10+-5 = 5`() {
            assertEquals(500, AmountUtils.parseExpression("10+-5"))
        }

        @Test
        fun `空字符串 → null`() = assertNull(AmountUtils.parseExpression(""))

        @Test
        fun `纯空白 → null`() = assertNull(AmountUtils.parseExpression("   "))

        @Test
        fun `非法字符 → null（不抛异常）`() = assertNull(AmountUtils.parseExpression("12abc"))

        @Test
        fun `除以零 → null（不抛 ArithmeticException）`() = assertNull(AmountUtils.parseExpression("5/0"))

        @Test
        fun `运算符缺失操作数 → null`() = assertNull(AmountUtils.parseExpression("5+"))

        @Test
        fun `多个运算符但只有一个操作数 → null`() = assertNull(AmountUtils.parseExpression("5+*3"))

        @Test
        fun `P1-4 回归：超大输入不被静默 clamp 成 Int 上限值，返回 null`() {
            // Kotlin 的 Double.roundToInt() 对超范围值是 **clamp 而非抛异常**：
            // 修复前这里返回 2147483647 分 = ¥21,474,836.47，一个看起来合法但完全错误的金额，
            // 而 20 个 9 刚好在 onAmountInput 的 20 字符限制内 → 用户可实际触发并存进账单。
            assertNull(AmountUtils.parseExpression("99999999999999999999"))
            // 同理，刚好在字符上限内的 20 个 9（onAmountInput 可达）
            assertNull(AmountUtils.parseExpression("99999999999999999999".take(20)))
        }

        @Test
        fun `P1-4 回归：超大负数同样被拦`() {
            assertNull(AmountUtils.parseExpression("-99999999999999999999"))
        }

        @Test
        fun `上限边界：恰好等于 MAX_AMOUNT_FEN 仍然通过（确认上限没误伤）`() {
            // ¥1,000,000 → 100,000,000 分
            assertEquals(AmountUtils.MAX_AMOUNT_FEN, AmountUtils.parseExpression("1000000"))
        }

        @Test
        fun `上限边界：刚超过上限即返回 null`() {
            assertNull(AmountUtils.parseExpression("1000000.01"))
        }

        @Test
        fun `Int 边界内仍然正常求值（确认上限没有误伤合法金额）`() {
            assertEquals(2_000_000, AmountUtils.parseExpression("20000"))
        }
    }
}
