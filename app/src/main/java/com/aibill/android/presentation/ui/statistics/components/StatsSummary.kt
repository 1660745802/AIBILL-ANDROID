package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.presentation.components.AmountFormat
import com.aibill.android.presentation.components.GradientSummaryCard
import com.aibill.android.presentation.components.Metric
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.ExpenseGradient
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.IncomeGradient
import com.aibill.android.presentation.theme.Tokens

/**
 * 统计页顶部汇总卡。基于 [GradientSummaryCard]，按 [selectedTab] 自动选支出/收入渐变。
 */
@Composable
fun SummaryCard(
    summary: StatsSummary?,
    selectedTab: String,
    daysInPeriod: Int = 1,
    modifier: Modifier = Modifier,
) {
    val displayAmount = when (selectedTab) {
        "expense" -> summary?.expense ?: 0
        else -> summary?.income ?: 0
    }
    val label = if (selectedTab == "expense") "总支出" else "总收入"
    val gradient = if (selectedTab == "expense") ExpenseGradient else IncomeGradient
    val change = when (selectedTab) {
        "expense" -> summary?.expenseChange ?: 0
        else -> summary?.incomeChange ?: 0
    }

    GradientSummaryCard(
        label = label,
        amountFen = displayAmount,
        modifier = modifier,
        gradient = gradient,
        trendPercent = change,
        secondaryMetrics = buildList {
            if (daysInPeriod > 0 && displayAmount > 0) {
                val dailyAvg = displayAmount.toFloat() / daysInPeriod / 100f
                add(Metric("日均", "¥${"%.2f".format(dailyAvg)}"))
            }
            if (summary != null && (summary.expense > 0 || summary.income > 0)) {
                if (selectedTab == "expense" && summary.income > 0) {
                    add(Metric("收入", AmountFormat.toYuanDisplay(summary.income)))
                } else if (selectedTab == "income" && summary.expense > 0) {
                    add(Metric("支出", AmountFormat.toYuanDisplay(summary.expense)))
                }
                if (summary.balance != 0) {
                    val balanceText = if (summary.balance < 0) {
                        "-${AmountFormat.toYuanDisplay(-summary.balance)}"
                    } else {
                        AmountFormat.toYuanDisplay(summary.balance)
                    }
                    add(Metric("结余", balanceText))
                }
            }
        },
    )
}

/**
 * 收支对比条：横向堆叠 progress bar。
 */
@Composable
fun IncomeExpenseCompareBar(
    expense: Int,
    income: Int,
    modifier: Modifier = Modifier,
) {
    if (expense <= 0 && income <= 0) return
    val total = (expense + income).coerceAtLeast(1)
    val expenseRatio = expense.toFloat() / total

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(Tokens.Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "支出 ${AmountFormat.toYuanDisplay(expense)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ExpenseColor,
                )
                Text(
                    "收入 ${AmountFormat.toYuanDisplay(income)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = IncomeColor,
                )
            }
            Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.xs)),
            ) {
                Box(
                    modifier = Modifier
                        .weight(expenseRatio.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(ExpenseColor),
                )
                Box(
                    modifier = Modifier
                        .weight((1f - expenseRatio).coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(IncomeColor),
                )
            }
        }
    }
}
