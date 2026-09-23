package com.aibill.android.presentation.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TrendPoint
import com.aibill.android.presentation.components.AmountFormat
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.GradientSummaryCard
import com.aibill.android.presentation.components.Metric
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.ExpenseGradient
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.IncomeGradient
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

@Composable
internal fun SummaryCard(
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
    // PR #55：按 selectedTab 切换 expense/income 环比
    val change = when (selectedTab) {
        "expense" -> summary?.expenseChange ?: 0
        else -> summary?.incomeChange ?: 0
    }

    // 使用统一的 GradientSummaryCard 组件
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

@Composable
internal fun IncomeExpenseCompareBar(
    expense: Int,
    income: Int,
    modifier: Modifier = Modifier,
) {
    if (expense <= 0 && income <= 0) return
    val total = (expense + income).coerceAtLeast(1)
    val expenseRatio = expense.toFloat() / total

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("支出 ${AmountFormat.toYuanDisplay(expense)}", style = MaterialTheme.typography.labelMedium, color = ExpenseColor)
                Text("收入 ${AmountFormat.toYuanDisplay(income)}", style = MaterialTheme.typography.labelMedium, color = IncomeColor)
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Stacked bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
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

@Composable
internal fun TrendChartPlaceholder(
    trendData: List<TrendPoint>,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val color = if (selectedTab == "expense") ExpenseColor else IncomeColor

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (trendData.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无趋势数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "📈 ${trendData.size}天趋势",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 自绘折线图（Canvas），避免引入 Vico 额外依赖
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val maxV = max(1, trendData.maxOf { it.amount })
                    val stepX = if (trendData.size > 1) w / (trendData.size - 1) else w

                    // 网格线（水平 3 条虚线）
                    val gridColor = Color(0x33888888)
                    for (i in 0..3) {
                        val y = h * i / 3
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }

                    // 折线
                    val path = Path()
                    val points = trendData.mapIndexed { idx, point ->
                        val x = stepX * idx
                        val y = h - (point.amount.toFloat() / maxV) * h * 0.9f - h * 0.05f
                        Offset(x, y)
                    }
                    points.forEachIndexed { idx, p ->
                        if (idx == 0) path.moveTo(p.x, p.y)
                        else path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 4f),
                    )

                    // 圆点
                    points.forEach { p ->
                        drawCircle(color = color, radius = 5f, center = p)
                        drawCircle(color = Color.White, radius = 2.5f, center = p)
                    }

                    // Average line
                    if (trendData.isNotEmpty()) {
                        val avgAmount = trendData.sumOf { it.amount }.toFloat() / trendData.size
                        val avgY = h - (avgAmount / maxV) * h * 0.9f - h * 0.05f
                        drawLine(
                            color = color.copy(alpha = 0.4f),
                            start = Offset(0f, avgY),
                            end = Offset(w, avgY),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                        )
                    }
                }

                // Max/Min annotations
                if (trendData.isNotEmpty()) {
                    val maxPoint = trendData.maxByOrNull { it.amount }
                    val minPoint = trendData.filter { it.amount > 0 }.minByOrNull { it.amount }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        maxPoint?.let {
                            Text(
                                text = "↑ 最高 ${AmountFormat.toYuanDisplay(it.amount)} (${it.date.takeLast(2)}日)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ExpenseColor,
                            )
                        }
                        minPoint?.let {
                            Text(
                                text = "↓ 最低 ${AmountFormat.toYuanDisplay(it.amount)} (${it.date.takeLast(2)}日)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分类占比环形图（Canvas 实现，避免 Vico 依赖）
 * 数据为空时显示提示。
 */
@Composable
internal fun CategoryDonutChart(
    categories: List<CategoryStat>,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val baseColor = if (selectedTab == "expense") ExpenseColor else IncomeColor

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = if (selectedTab == "expense") "支出构成" else "收入构成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (categories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val strokeWidth = 28f
                            val arcSize = Size(w - strokeWidth, h - strokeWidth)
                            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                            // 背景圆
                            drawArc(
                                color = Color(0x22888888),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth),
                            )

                            // 各段
                            var startAngle = -90f
                            val total = categories.sumOf { it.amount }.coerceAtLeast(1)
                            categories.forEachIndexed { idx, cat ->
                                val sweep = (cat.amount.toFloat() / total) * 360f
                                val segColor = baseColor.copy(
                                    alpha = 1f - idx * 0.12f.coerceAtMost(0.7f)
                                )
                                drawArc(
                                    color = segColor,
                                    startAngle = startAngle,
                                    sweepAngle = sweep - 2f, // 段间留 2° 缝隙
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth),
                                )
                                startAngle += sweep
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categories.take(5).forEachIndexed { idx, cat ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = baseColor.copy(alpha = 1f - idx * 0.12f.coerceAtMost(0.7f)),
                                    modifier = Modifier.size(10.dp),
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${cat.categoryIcon} ${cat.categoryName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    text = "${"%.0f".format(cat.percent)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryStatItem(
    category: CategoryStat,
    selectedTab: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progressColor = if (selectedTab == "expense") ExpenseColor else IncomeColor
    val displayIcon = category.categoryIcon

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(40.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = displayIcon, fontSize = 18.sp)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = category.categoryName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${"%.1f".format(category.percent)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (category.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = progressColor.copy(alpha = 0.85f),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = AmountFormat.toYuanDisplay(category.amount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = progressColor,
            )
        }
    }
}
