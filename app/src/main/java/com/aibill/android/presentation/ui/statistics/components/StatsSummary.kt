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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AmountHero
import com.aibill.android.presentation.components.AppHeroCard
import com.aibill.android.presentation.components.StatCell
import com.aibill.android.presentation.components.TrendIndicator
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 统计页顶部汇总卡（Hero 卡）。
 *
 * 结构（自上而下）：
 * - label（总支出/总收入）+ 右侧 [TrendIndicator] 环比。
 * - [AmountHero] 36sp 等宽大数字（¥ 自动缩小）。
 * - 一行 [StatCell]：日均 / 结余 / 笔数（无笔数时只放前两格，用竖线分隔）。
 *
 * 整卡可点击 → 跳当期流水（透传 [onClick]）。
 *
 * @param count 当期笔数；VM 暂无该字段时传 null，则不显示「笔数」格。
 */
@Composable
fun SummaryCard(
    summary: StatsSummary?,
    selectedTab: String,
    daysInPeriod: Int = 1,
    count: Int? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isExpense = selectedTab == "expense"
    val displayAmount = if (isExpense) summary?.expense ?: 0 else summary?.income ?: 0
    val label = if (isExpense) "总支出" else "总收入"
    val type = if (isExpense) TransactionType.EXPENSE else TransactionType.INCOME
    val change = if (isExpense) summary?.expenseChange ?: 0 else summary?.incomeChange ?: 0

    AppHeroCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 支出环比：增加=红、减少=绿（默认行为）。收入则反转：增加=绿。
            TrendIndicator(percent = change ?: 0, inverted = !isExpense)
        }
        Spacer(Modifier.height(Tokens.Spacing.sm))
        AmountHero(amount = displayAmount, type = type)

        val dailyAvg: String? = if (daysInPeriod > 0 && displayAmount > 0) {
            AmountFormatter.toYuanDisplay(displayAmount / daysInPeriod)
        } else {
            null
        }
        // 格式化器自己处理负号，直接传即可。
        // （旧写法手工拼 "-${toYuanDisplay(-balance)}"，双重取负很容易写错。）
        val balanceText: String? = summary?.let { AmountFormatter.toYuanDisplay(it.balance) }

        Spacer(Modifier.height(Tokens.Spacing.lg))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "日均",
                value = dailyAvg ?: "¥0.00",
                valueStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = "结余",
                value = balanceText ?: "¥0.00",
                valueColor = if ((summary?.balance ?: 0) < 0) {
                    MaterialTheme.semantic.expense
                } else {
                    MaterialTheme.semantic.income
                },
                valueStyle = MaterialTheme.typography.titleMedium,
                showDividerBefore = true,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                StatCell(
                    label = "笔数",
                    value = count.toString(),
                    valueStyle = MaterialTheme.typography.titleMedium,
                    showDividerBefore = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 收支对比条：汇总卡**下方紧贴**的一条 6dp 圆角比例条。
 *
 * 不再是独立卡片——它就是「本月净支出 or 净收入」的一眼判断。
 * 两端直接标金额，中间无标题；支出段用 `semantic.expense`，收入段用 `semantic.income`。
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
    val expenseColor = MaterialTheme.semantic.expense
    val incomeColor = MaterialTheme.semantic.income

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tokens.Chart.barThickness)
                .clip(RoundedCornerShape(Tokens.Radius.xs)),
        ) {
            if (expense > 0) {
                Box(
                    modifier = Modifier
                        .weight(expenseRatio.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(expenseColor),
                )
            }
            if (income > 0) {
                Box(
                    modifier = Modifier
                        .weight((1f - expenseRatio).coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(incomeColor),
                )
            }
        }
        Spacer(Modifier.height(Tokens.Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "支出 ${AmountFormatter.toYuanDisplay(expense)}",
                style = MaterialTheme.typography.labelMedium,
                color = expenseColor,
                maxLines = 1,
            )
            Text(
                text = "收入 ${AmountFormatter.toYuanDisplay(income)}",
                style = MaterialTheme.typography.labelMedium,
                color = incomeColor,
                maxLines = 1,
            )
        }
    }
}
