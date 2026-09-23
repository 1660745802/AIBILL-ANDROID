package com.aibill.android.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.BrandGradient
import com.aibill.android.presentation.theme.ExpenseGradient
import com.aibill.android.presentation.theme.IncomeGradient
import com.aibill.android.presentation.theme.Tokens

/**
 * 渐变汇总卡。统一替代：
 * - HomeScreen.MonthlyExpenseHeader
 * - StatsComponents.SummaryCard
 * - ProfileScreen 的渐变 header
 *
 * 设计要点：
 * - 圆角 xl（20dp）
 * - 主金额大字号 + 白色
 * - 底部 meta 行（小字、半透明白）
 * - 可选主金额下方的"环比变化"指示器
 */
@Composable
fun GradientSummaryCard(
    label: String,
    amountFen: Int,
    modifier: Modifier = Modifier,
    gradient: Brush = BrandGradient,
    periodLabel: String? = null,
    trendPercent: Int? = null,
    secondaryMetrics: List<Metric> = emptyList(),
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = gradient, shape = RoundedCornerShape(Tokens.Radius.xl))
                .padding(horizontal = Tokens.Spacing.xxl, vertical = Tokens.Spacing.xl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(Tokens.Spacing.sm))
                    Text(
                        text = AmountFormat.toYuanDisplay(amountFen),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(-0.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                        ),
                        color = Color.White,
                    )
                }
                if (periodLabel != null) {
                    Text(
                        text = periodLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // 趋势
            if (trendPercent != null) {
                Spacer(Modifier.height(Tokens.Spacing.sm))
                TrendIndicator(percent = trendPercent)
            }

            // 副指标（收入/支出/日均 等）
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
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendIndicator(percent: Int) {
    val isUp = percent >= 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isUp) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
            contentDescription = null,
            modifier = Modifier.size(Tokens.IconSize.sm),
            tint = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(Tokens.Spacing.xs))
        Text(
            text = if (isUp) "环比增长 ${percent}%" else "环比减少 ${-percent}%",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

/**
 * 副指标条目：[Metric] 用于 [GradientSummaryCard.secondaryMetrics]
 */
data class Metric(val label: String, val value: String)

/**
 * 预定义渐变。供屏幕选择：
 * - [BrandGradient]  → 中性首页
 * - [IncomeGradient] → 收入主题
 * - [ExpenseGradient]→ 支出主题
 *
 * 通过 [gradient] 参数传入 [GradientSummaryCard]。
 */
object CardGradients {
    val Brand: Brush get() = BrandGradient
    val Income: Brush get() = IncomeGradient
    val Expense: Brush get() = ExpenseGradient
}
