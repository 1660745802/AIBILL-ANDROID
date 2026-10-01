package com.aibill.android.util

import java.util.Stack
import kotlin.math.roundToInt

/**
 * 金额工具类，统一使用"分"作为内部存储单位，避免浮点精度问题。
 */
object AmountUtils {

    /**
     * 单笔记账金额上限：¥1,000,000（= 100,000,000 分）。
     *
     * 纯防御性上限：个人日常记账远超此值即为误输入；更重要的是它拦住了
     * `Double.roundToInt()` 静默 clamp 成 Int.MAX_VALUE 的问题（见 [parseExpression]）。
     */
    const val MAX_AMOUNT_FEN = 100_000_000

    /**
     * 分转元字符串（保留2位小数）
     * @param fen 金额（分）
     * @return 格式化的元字符串，如 "12.50"
     */
    fun fenToYuan(fen: Int): String {
        return "%.2f".format(fen / 100.0)
    }

    /**
     * 计算器表达式求值（支持 +、-、*、/），结果为分。
     * 输入的数字视为“元”，结果转为“分”返回。
     *
     * 超出 [MAX_AMOUNT_FEN] 范围时返回 null。
     *
     * ⚠️ 必须显式拦上限：Kotlin 的 `Double.roundToInt()` 对超范围值**静默 clamp 到
     * Int.MAX_VALUE**（而不是抛异常）——所以 “99999999999999999999” 会变成
     * 2147483647 分 = ¥21,474,836.47，一个**看起来合法但完全错误**的金额，
     * 会被当作用户输入存进账单。而 20 个 9 刚好在 onAmountInput 的长度限制内，可实际触发。
     *
     * @param expr 表达式字符串，如 “10.5+3.2*2”
     * @return 计算结果（分），无效表达式或超限返回 null
     */
    fun parseExpression(expr: String): Int? {
        return try {
            val trimmed = expr.trim()
            if (trimmed.isEmpty()) return null
            val result = evaluate(trimmed) ?: return null
            // 先在 Double 域做有限性 + 范围判断，再做 roundToInt（避免被 clamp）
            if (!result.isFinite() ||
                result > MAX_AMOUNT_FEN / 100.0 ||
                result < -MAX_AMOUNT_FEN / 100.0
            ) {
                return null
            }
            (result * 100).roundToInt()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 简单的四则运算表达式求值（不支持括号）
     * 使用双栈法：运算符栈 + 操作数栈，处理优先级
     */
    private fun evaluate(expr: String): Double? {
        val numStack = Stack<Double>()
        val opStack = Stack<Char>()

        var i = 0
        val len = expr.length

        while (i < len) {
            val c = expr[i]

            when {
                c == ' ' -> {
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < len && (expr[i].isDigit() || expr[i] == '.')) {
                        sb.append(expr[i])
                        i++
                    }
                    val num = sb.toString().toDoubleOrNull() ?: return null
                    numStack.push(num)
                }
                c == '+' || c == '-' || c == '*' || c == '/' -> {
                    // 处理负号在表达式开头或运算符之后的情况
                    if (c == '-' && (i == 0 || expr[i - 1] in "+-*/")) {
                        val sb = StringBuilder("-")
                        i++
                        while (i < len && (expr[i].isDigit() || expr[i] == '.')) {
                            sb.append(expr[i])
                            i++
                        }
                        val num = sb.toString().toDoubleOrNull() ?: return null
                        numStack.push(num)
                    } else {
                        while (opStack.isNotEmpty() && precedence(opStack.peek()) >= precedence(c)) {
                            if (!applyOp(numStack, opStack)) return null
                        }
                        opStack.push(c)
                        i++
                    }
                }
                else -> return null // 非法字符
            }
        }

        while (opStack.isNotEmpty()) {
            if (!applyOp(numStack, opStack)) return null
        }

        return if (numStack.size == 1) numStack.pop() else null
    }

    private fun precedence(op: Char): Int {
        return when (op) {
            '+', '-' -> 1
            '*', '/' -> 2
            else -> 0
        }
    }

    private fun applyOp(numStack: Stack<Double>, opStack: Stack<Char>): Boolean {
        if (numStack.size < 2) return false
        val b = numStack.pop()
        val a = numStack.pop()
        val op = opStack.pop()
        val result = when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> {
                if (b == 0.0) return false
                a / b
            }
            else -> return false
        }
        numStack.push(result)
        return true
    }
}
