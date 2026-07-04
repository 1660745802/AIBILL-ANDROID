package com.aibill.android.presentation.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.utils.toYuanDisplay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

private val ExpenseGradient = listOf(Color(0xFFF44336), Color(0xFFFF7043))
private val IncomeGradient = listOf(Color(0xFF4CAF50), Color(0xFF81C784))

@Composable
internal fun SummaryCard(
    summary: StatisticsViewModel.StatsSummary?,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val displayAmount = when (selectedTab) {
        "expense" -> summary?.expense ?: 0
        else -> summary?.income ?: 0
    }
    val label = if (selectedTab == "expense") "总支出" else "总收入"
    val gradient = if (selectedTab == "expense") ExpenseGradient else IncomeGradient
    val change = summary?.expenseChange ?: 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = gradient,
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(24.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayAmount.toYuanDisplay(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.5).sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (change >= 0) {
                        Icons.Default.TrendingUp
                    } else {
                        Icons.Default.TrendingDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
                Spacer(modifier = Modifier.width(4.dp))
                val changeText = if (change >= 0) "环比增长 ${change}%"
                else "环比减少 ${-change}%"
                Text(
                    text = changeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
internal fun TrendChartPlaceholder(
    trendData: List<StatisticsViewModel.TrendPoint>,
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
                .height(200.dp)
                .padding(16.dp),
        ) {
            if (trendData.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
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
    categories: List<StatisticsViewModel.CategoryStat>,
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
    category: StatisticsViewModel.CategoryStat,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val progressColor = if (selectedTab == "expense") ExpenseColor else IncomeColor

    Card(
        modifier = modifier.fillMaxWidth(),
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
                    Text(text = category.categoryIcon, fontSize = 18.sp)
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
                text = category.amount.toYuanDisplay(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = progressColor,
            )
        }
    }
}
