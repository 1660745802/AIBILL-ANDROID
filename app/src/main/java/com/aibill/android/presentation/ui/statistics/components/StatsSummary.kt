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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.Metric
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens

/**
 * 统计页顶部汇总卡。低调表面色背景 + 强调色文字，**不使用渐变**。
 * 按 [selectedTab] 切换强调色（支出 → 红，收入 → 绿）。
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
    val amountColor = if (selectedTab == "expense") ExpenseColor else IncomeColor
    val change = when (selectedTab) {
        "expense" -> summary?.expenseChange ?: 0
        else -> summary?.incomeChange ?: 0
    }

    val secondaryMetrics = buildList<Metric> {
        if (daysInPeriod > 0 && displayAmount > 0) {
            val dailyAvg = displayAmount.toFloat() / daysInPeriod / 100f
            add(Metric("日均", "¥${"%.2f".format(dailyAvg)}"))
        }
        if (summary != null && (summary.expense > 0 || summary.income > 0)) {
            if (selectedTab == "expense" && summary.income > 0) {
                add(Metric("收入", AmountFormatter.toYuanDisplay(summary.income)))
            } else if (selectedTab == "income" && summary.expense > 0) {
                add(Metric("支出", AmountFormatter.toYuanDisplay(summary.expense)))
            }
            if (summary.balance != 0) {
                val balanceText = if (summary.balance < 0) {
                    "-${AmountFormatter.toYuanDisplay(-summary.balance)}"
                } else {
                    AmountFormatter.toYuanDisplay(summary.balance)
                }
                add(Metric("结余", balanceText))
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Spacing.xxl, vertical = Tokens.Spacing.xl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Tokens.Spacing.sm))
                    Text(
                        text = AmountFormatter.toYuanDisplay(displayAmount),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                    )
                }
                if (change != 0) {
                    Text(
                        text = if (change > 0) "环比 +$change%" else "环比 $change%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (secondaryMetrics.isNotEmpty()) {
                Spacer(Modifier.height(Tokens.Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    secondaryMetrics.forEach { metric ->
                        Text(
                            text = "${metric.label} ${metric.value}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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
                    "支出 ${AmountFormatter.toYuanDisplay(expense)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ExpenseColor,
                )
                Text(
                    "收入 ${AmountFormatter.toYuanDisplay(income)}",
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
