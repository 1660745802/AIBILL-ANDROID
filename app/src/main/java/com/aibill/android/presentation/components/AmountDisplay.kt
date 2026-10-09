package com.aibill.android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import java.util.Locale

/**
 * 金额格式化与显示工具。
 *
 * 金额以「分」为单位存储，显示时按业务类型着色：
 * - expense → 红 + `-` 前缀
 * - income  → 绿 + `+` 前缀
 * - transfer → 中性色，无前缀
 *
 * 隐私模式（[privacy] = true）下显示 `¥***`。
 */
object AmountFormatter {
    /**
     * 分 → "-¥32.50" / "¥32.50"
     *
     * ⚠️ 两个必须做对的点：
     * 1. **负号在 ¥ 前面**（`-¥32.50`，不是 `¥-32.50`）。符号跟数值走。
     * 2. **整数部分用向下取整而不是 Java 的截断**。`-50` 分如果写成 `cents / 100`
     *    会得到 `0`，输出 `¥0.50` —— 符号和角分全丢。负数要先取绝对值再除。
     *
     * 业务上 `Transaction.amount` 恒为正（正负由 type 决定），但 `balance`、
     * `targetBalance` 这类派生值可能是负的，格式化器必须自己站得住。
     */
    fun toYuanDisplay(amountFen: Int): String {
        val negative = amountFen < 0
        val abs = Math.abs(amountFen)
        return (if (negative) "-" else "") +
            "¥" + String.format(Locale.US, "%,d.%02d", abs / 100, abs % 100)
    }

    /**
     * 分 → 带业务语义符号的显示：expense 恒 `-`、income 恒 `+`、transfer 不带。
     *
     * **符号跟业务类型走，不跟数值正负走**。所以负数也不会出现 `-¥-32.50`
     * 这种双符号 —— 符号位由类型决定，数值只决定绝对值部分。
     */
    fun toSignedDisplay(amountFen: Int, type: TransactionType): String {
        val sign = when (type) {
            TransactionType.EXPENSE -> "-"
            TransactionType.INCOME -> "+"
            TransactionType.TRANSFER -> ""
        }
        return sign + "¥" + String.format(
            Locale.US,
            "%,d.%02d",
            Math.abs(amountFen) / 100,
            Math.abs(amountFen) % 100,
        )
    }

    /** 分 → 千分位无小数（用于超大额的紧凑展示，如 ¥12,345）；负数保留符号 */
    fun toCompactDisplay(amountFen: Int): String {
        val negative = amountFen < 0
        val abs = Math.abs(amountFen)
        return (if (negative) "-" else "") +
            "¥" + String.format(Locale.US, "%,d", abs / 100)
    }

    /**
     * 分 → 图表轴标签；负数保留符号。
     *
     * ⚠️ 「万」的换算基数是 **1,000,000 分 = ¥10,000**，所以进入万位制的门槛
     * 也必须是 1,000,000 分。早先写成 `abs >= 100_000`（¥1,000），
     * 结果 ¥1,234 被折成「0.1万」——轴标签完全失去意义。
     *
     * - ¥1,234   → "1,234"
     * - ¥12,345  → "1.2万"
     * - ¥123,456 → "12.3万"
     */
    fun toAxisLabel(amountFen: Int): String {
        val negative = amountFen < 0
        val abs = Math.abs(amountFen)
        val body = if (abs >= WAN_FEN) {
            String.format(Locale.US, "%.1f万", abs / WAN_FEN.toDouble())
        } else {
            String.format(Locale.US, "%,d", abs / 100)
        }
        return (if (negative) "-" else "") + body
    }

    /** 一「万」等于多少分（= ¥10,000） */
    private const val WAN_FEN = 1_000_000

    /** 隐私遮罩：固定 "¥***" */
    const val PRIVACY_MASK = "¥***"
}

/**
 * 统一金额显示（单行、等宽数字）。
 *
 * 用法：
 * ```
 * AmountText(amount = 3250, type = TransactionType.EXPENSE)
 * AmountText(amount = 3250, privacy = true)  // 显示 ¥***
 * AmountText(amount = 3250, type = EXPENSE, style = AmountTypography.Large)
 * ```
 */
@Composable
fun AmountText(
    amount: Int,
    modifier: Modifier = Modifier,
    type: TransactionType? = null,
    privacy: Boolean = false,
    style: TextStyle = AmountTypography.Row,
    color: Color? = null,
    showSign: Boolean = true,
    maxLines: Int = 1,
) {
    val text = when {
        privacy -> AmountFormatter.PRIVACY_MASK
        type != null && showSign -> AmountFormatter.toSignedDisplay(amount, type)
        else -> AmountFormatter.toYuanDisplay(amount)
    }
    val semantics = MaterialTheme.semantic
    val finalColor = color ?: when (type) {
        TransactionType.EXPENSE -> semantics.expense
        TransactionType.INCOME -> semantics.income
        TransactionType.TRANSFER -> semantics.transfer
        null -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = finalColor,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 大额金额（首页月度支出 / 统计汇总）。
 *
 * 细节：`¥` 符号用 0.62 倍字号并抬升一点基线，数字用全尺寸——
 * 这样 36sp 的总额实际视觉重量接近 32sp，不会把卡片挤满，
 * 同时货币单位依然一眼可辨，不需要用户读两遍才确认这是钱。
 *
 * @param trend 环比变化百分比。null 不显示；正数红（多花了），负数绿（省了）。
 */
@Composable
fun AmountHero(
    amount: Int,
    modifier: Modifier = Modifier,
    type: TransactionType = TransactionType.EXPENSE,
    color: Color? = null,
    currencySize: TextUnit? = null,
) {
    val semantics = MaterialTheme.semantic
    val finalColor = color ?: when (type) {
        TransactionType.EXPENSE -> semantics.expense
        TransactionType.INCOME -> semantics.income
        TransactionType.TRANSFER -> semantics.transfer
    }
    Row(modifier = modifier, verticalAlignment = AlignmentBaseline) {
        Text(
            text = "¥",
            style = AmountTypography.Hero.copy(
                color = finalColor.copy(alpha = 0.72f),
                fontSize = currencySize ?: (AmountTypography.Hero.fontSize * 0.6f),
            ),
        )
        Text(
            text = moneyDigits(amount),
            style = AmountTypography.Hero.copy(color = finalColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 拆出 ¥ 后面的小数部分（Hero 专用：小数固定 2 位，不足补 0）。 */
private fun moneyDigits(amountFen: Int): String =
    String.format(Locale.US, "%,d.%02d", amountFen / 100, Math.abs(amountFen % 100))

private val AlignmentBaseline = androidx.compose.ui.Alignment.CenterVertically

/**
 * 标题级大金额（首页月度支出 / 详情页主金额）。
 */
@Composable
fun AmountHeadline(
    amount: Int,
    modifier: Modifier = Modifier,
    type: TransactionType? = null,
) {
    AmountText(
        amount = amount,
        modifier = modifier,
        type = type,
        style = AmountTypography.Large,
        showSign = false,
    )
}

/**
 * 同比/环比指示器。
 *
 * 设计决定：只说方向和幅度，不说"好/坏"。对支出来说数字上升是中性事实，
 * 用红色表达"变糟"是一种说教。这里红色 = 支出增加、绿色 = 支出减少，
 * 保持"红出绿进"的一致语义，让用户自己判断。
 */
@Composable
fun TrendIndicator(
    percent: Int,
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
    suffix: String = "较上月",
) {
    if (percent == 0) {
        Text(
            text = suffix + "持平",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = modifier,
        )
        return
    }
    val rising = if (inverted) percent < 0 else percent > 0
    val tone = if (rising) {
        MaterialTheme.semantic.expense
    } else {
        MaterialTheme.semantic.income
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        Text(
            text = if (rising) "↑" else "↓",
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
        Text(
            text = "${kotlin.math.abs(percent)}% $suffix",
            style = MaterialTheme.typography.labelSmall,
            color = tone,
        )
    }
}
