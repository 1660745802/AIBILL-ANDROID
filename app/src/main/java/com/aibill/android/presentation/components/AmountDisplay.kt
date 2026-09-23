package com.aibill.android.presentation.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.TransferColor

/**
 * 金额格式化与显示工具。
 *
 * 金额以「分」为单位存储，显示时按业务类型着色：
 * - expense → 红色 + `-` 前缀
 * - income  → 绿色 + `+` 前缀
 * - transfer → 中性色，无前缀
 *
 * 隐私模式（[privacy] = true）下显示 `¥***`。
 */
object AmountFormat {
    /** 分 → "¥32.00" */
    fun toYuanDisplay(amountFen: Int): String = "¥${String.format("%.2f", amountFen / 100.0)}"

    /** 分 → 完整带符号显示 "+¥32.00" / "-¥32.00" / "¥32.00" */
    fun toSignedDisplay(amountFen: Int, type: TransactionType): String {
        val sign = when (type) {
            TransactionType.EXPENSE -> "-"
            TransactionType.INCOME -> "+"
            TransactionType.TRANSFER -> ""
        }
        return "$sign${toYuanDisplay(amountFen)}"
    }

    /** 隐私遮罩：固定 "¥***" */
    const val PRIVACY_MASK = "¥***"
}

/**
 * 统一金额显示。
 *
 * 用法：
 * ```
 * AmountText(amount = 3250, type = TransactionType.EXPENSE)
 * AmountText(amount = 3250, privacy = true)  // 显示 ¥***
 * AmountText(amount = 3250, type = EXPENSE, style = titleLarge)
 * ```
 */
@Composable
fun AmountText(
    amount: Int,
    modifier: Modifier = Modifier,
    type: TransactionType? = null,
    privacy: Boolean = false,
    style: TextStyle = LocalTextStyle.current,
    color: Color? = null,
    showSign: Boolean = true,
) {
    val text = when {
        privacy -> AmountFormat.PRIVACY_MASK
        type != null && showSign -> AmountFormat.toSignedDisplay(amount, type)
        else -> AmountFormat.toYuanDisplay(amount)
    }
    val finalColor = color ?: when (type) {
        TransactionType.EXPENSE -> ExpenseColor
        TransactionType.INCOME -> IncomeColor
        TransactionType.TRANSFER -> TransferColor
        null -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = finalColor,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/**
 * 标题级大金额（如首页月度支出）。无前缀，自动按 type 着色。
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
        style = MaterialTheme.typography.headlineMedium,
        showSign = false,
    )
}
