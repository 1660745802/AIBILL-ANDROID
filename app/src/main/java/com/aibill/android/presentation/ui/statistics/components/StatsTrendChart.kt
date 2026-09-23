package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.repository.TrendPoint
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens
import kotlin.math.max

/**
 * 趋势折线图（自绘 Canvas）。
 *
 * 为避免引入 Vico 等重型图表库，使用原生 Canvas 绘制：
 * - 3 条水平虚线网格
 * - 折线 + 圆点
 * - 平均线（虚线）
 * - 最高/最低标注
 */
@Composable
fun TrendChartPlaceholder(
    trendData: List<TrendPoint>,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val color = if (selectedTab == "expense") ExpenseColor else IncomeColor

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.lg),
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
                Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
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
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path = path, color = color, style = Stroke(width = 4f))

                    // 圆点
                    points.forEach { p ->
                        drawCircle(color = color, radius = 5f, center = p)
                        drawCircle(color = Color.White, radius = 2.5f, center = p)
                    }

                    // Average line
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

                // Max/Min 标注
                val maxPoint = trendData.maxByOrNull { it.amount }
                val minPoint = trendData.filter { it.amount > 0 }.minByOrNull { it.amount }
                Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    maxPoint?.let {
                        Text(
                            text = "↑ 最高 ${AmountFormatter.toYuanDisplay(it.amount)} (${it.date.takeLast(2)}日)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ExpenseColor,
                        )
                    }
                    minPoint?.let {
                        Text(
                            text = "↓ 最低 ${AmountFormatter.toYuanDisplay(it.amount)} (${it.date.takeLast(2)}日)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
