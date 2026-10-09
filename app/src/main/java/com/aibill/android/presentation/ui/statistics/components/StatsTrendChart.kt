package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.aibill.android.domain.repository.TrendPoint
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AppCard
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import java.time.LocalDate
import kotlin.math.max

/**
 * 消费趋势折线图（自绘 Canvas，不引第三方图表库）。
 *
 * 升级点（相对旧的 TrendChartPlaceholder）：
 * - 折线下方同色渐变填充（顶 alpha 0.22 → 底 0），面积感成立。
 * - 水平网格线用 `semantic.chartGrid`。
 * - 平均值虚线（dashPathEffect）+ 右侧平均值数值。
 * - 当前月「今天」实心高亮点 + 两层光晕。
 * - X 轴首/中/末日期标签；Y 轴左侧最大值 + 中值标签（[AmountFormatter.toAxisLabel]）。
 * - 空态为图标化 [androidx.compose.material.icons.outlined.ShowChart]。
 */
@Composable
fun TrendChart(
    trendData: List<TrendPoint>,
    selectedTab: String,
    year: Int,
    month: Int,
    modifier: Modifier = Modifier,
) {
    val lineColor = if (selectedTab == "expense") {
        MaterialTheme.semantic.expense
    } else {
        MaterialTheme.semantic.income
    }
    val gridColor = MaterialTheme.semantic.chartGrid
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val axisStyle: TextStyle = MaterialTheme.typography.labelSmall.copy(color = axisTextColor)

    AppCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SectionHeader(title = "消费趋势", subtitle = "近 ${trendData.size.coerceAtLeast(30)} 天")
        Spacer(Modifier.height(Tokens.Spacing.md))

        if (trendData.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Chart.canvasHeight),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(Tokens.IconSize.xxl),
                    )
                    Spacer(Modifier.height(Tokens.Spacing.sm))
                    Text(
                        text = "这个月还没有记账",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val todayIndex = resolveTodayIndex(trendData, year, month)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Chart.canvasHeight),
            ) {
                val w = size.width
                val h = size.height
                // 左留白给 Y 轴标签，底部留白给 X 轴标签。
                val leftPad = 44f
                val bottomPad = 26f
                val topPad = 8f
                val plotW = w - leftPad
                val plotH = h - bottomPad - topPad
                val maxV = max(1, trendData.maxOf { it.amount })
                val stepX = if (trendData.size > 1) plotW / (trendData.size - 1) else plotW

                fun xAt(idx: Int) = leftPad + stepX * idx
                fun yAt(amount: Int) = topPad + plotH - (amount.toFloat() / maxV) * plotH

                // ── 水平网格线（3 条）──
                for (i in 0..3) {
                    val y = topPad + plotH * i / 3
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPad, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }

                // ── Y 轴标签：最大值（顶）+ 中值 ──
                val maxLabel = textMeasurer.measure(AmountFormatter.toAxisLabel(maxV), axisStyle)
                drawText(
                    textLayoutResult = maxLabel,
                    topLeft = Offset(0f, topPad - maxLabel.size.height / 2f),
                )
                val midLabel = textMeasurer.measure(AmountFormatter.toAxisLabel(maxV / 2), axisStyle)
                drawText(
                    textLayoutResult = midLabel,
                    topLeft = Offset(0f, topPad + plotH / 2f - midLabel.size.height / 2f),
                )

                val points = trendData.mapIndexed { idx, p -> Offset(xAt(idx), yAt(p.amount)) }

                // ── 渐变面积填充（折线下方）──
                val fillPath = Path().apply {
                    moveTo(points.first().x, topPad + plotH)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, topPad + plotH)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.22f),
                            lineColor.copy(alpha = 0f),
                        ),
                        startY = topPad,
                        endY = topPad + plotH,
                    ),
                )

                // ── 折线 ──
                val linePath = Path()
                points.forEachIndexed { idx, p ->
                    if (idx == 0) linePath.moveTo(p.x, p.y) else linePath.lineTo(p.x, p.y)
                }
                drawPath(path = linePath, color = lineColor, style = Stroke(width = 4f))

                // ── 平均值虚线 + 右侧数值 ──
                val avgAmount = (trendData.sumOf { it.amount }.toFloat() / trendData.size).toInt()
                val avgY = yAt(avgAmount)
                drawLine(
                    color = lineColor.copy(alpha = 0.5f),
                    start = Offset(leftPad, avgY),
                    end = Offset(w, avgY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
                val avgLabel = textMeasurer.measure("均 ${AmountFormatter.toAxisLabel(avgAmount)}", axisStyle)
                drawText(
                    textLayoutResult = avgLabel,
                    topLeft = Offset(
                        x = (w - avgLabel.size.width).coerceAtLeast(leftPad),
                        y = (avgY - avgLabel.size.height - 2f).coerceAtLeast(topPad),
                    ),
                )

                // ── 「今天」高亮点 + 两层光晕 ──
                if (todayIndex in points.indices) {
                    val tp = points[todayIndex]
                    drawCircle(color = lineColor.copy(alpha = 0.18f), radius = 16f, center = tp)
                    drawCircle(color = lineColor.copy(alpha = 0.32f), radius = 9f, center = tp)
                    drawCircle(color = lineColor, radius = 5f, center = tp)
                }

                // ── X 轴首 / 中 / 末日期标签 ──
                val lastIdx = trendData.size - 1
                val labelIdx = listOf(0, lastIdx / 2, lastIdx).distinct()
                labelIdx.forEach { idx ->
                    val txt = formatAxisDate(trendData[idx].date)
                    val layout = textMeasurer.measure(txt, axisStyle)
                    val cx = (xAt(idx) - layout.size.width / 2f)
                        .coerceIn(leftPad, w - layout.size.width)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(cx, h - layout.size.height),
                    )
                }
            }
        }
    }
}

/**
 * 定位趋势序列中「今天」的下标：仅当查看的是当前月才有意义。
 * 匹配不到（历史月份 / 日期格式差异）返回 -1。
 */
private fun resolveTodayIndex(trendData: List<TrendPoint>, year: Int, month: Int): Int {
    val now = LocalDate.now()
    if (year != now.year || month != now.monthValue) return -1
    val today = now.dayOfMonth
    return trendData.indexOfLast { point ->
        point.date.takeLast(2).toIntOrNull() == today
    }
}

/** `yyyy-MM-dd` / `MM-dd` / `dd` → `M/d`，兜底原串末两位。 */
private fun formatAxisDate(raw: String): String {
    val parts = raw.split("-")
    return when (parts.size) {
        3 -> "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
        2 -> "${parts[0].toIntOrNull() ?: parts[0]}/${parts[1].toIntOrNull() ?: parts[1]}"
        else -> raw.takeLast(2)
    }
}
