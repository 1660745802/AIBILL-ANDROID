package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.roundToInt

/**
 * 绘图区内边距（px）。
 *
 * **必须提到文件级**：交互层（`pointerInput` 的 x → 下标映射）和绘制层（Canvas）
 * 要用同一套 padding。以前这些值是 DrawScope 里的局部 `val`，交互层无从复用，
 * 一旦两边对不齐就会出现「手指按在 A 点、高亮却跳到 B 点」。
 */
private const val TREND_LEFT_PAD = 44f
private const val TREND_TOP_PAD = 8f
private const val TREND_BOTTOM_PAD = 26f

/** Tooltip 药丸的内边距（px）。 */
private const val TOOLTIP_PAD_H = 8f
private const val TOOLTIP_PAD_V = 5f

/**
 * 触摸横坐标 → 趋势序列下标。
 *
 * 取最近点（四舍五入）而不是向下取整：手指落点离哪个点近就选哪个，
 * 避免「明明按在 15 号却高亮了 14 号」。
 *
 * 画布左侧留白区（x < [TREND_LEFT_PAD]）统一归到 0 号，
 * 右侧越界由 `coerceIn` 兜到最后一个点——手指滑出画布也不丢选中。
 *
 * 抽成纯函数是为了能直接单测（手指坐标换算是最容易写错又最难肉眼验证的一段）。
 */
internal fun trendIndexForX(x: Float, canvasWidth: Float, count: Int): Int {
    if (count <= 0) return -1
    if (count == 1) return 0
    val plotW = (canvasWidth - TREND_LEFT_PAD).coerceAtLeast(1f)
    val stepX = plotW / (count - 1)
    return ((x - TREND_LEFT_PAD) / stepX).roundToInt().coerceIn(0, count - 1)
}

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
 *
 * ## 交互（v2）
 *
 * 原来这张图是纯静态 Canvas：160dp 的折线只能看出「涨了还是跌了」，
 * 想确认「哪天花的、那天多少」无从下手，而消费趋势图最该回答的就是这一个问题。
 *
 * 现在支持 **点按 / 按住横向拖动**：
 * - 手指按下即选中最近的一天，随后拖动实时跟随（抬手后选中态保留）
 * - 选中处画竖直虚线 + 放大的高亮点 + 顶部药丸（`M/d · ¥金额`）
 * - 查看当月时默认选中「今天」，首屏就有信息，不需要先猜「这里能点」
 *
 * 选中态随 [trendData] / [year] / [month] / [selectedTab] 变化重置——
 * 切月或切换收支 Tab 后旧下标已无意义，继续保留会指到别人的数据上。
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

    // Tooltip 配色必须在 Canvas 外取：DrawScope 不是 @Composable 作用域。
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val tooltipTextStyle: TextStyle = MaterialTheme.typography.labelMedium
        .copy(color = MaterialTheme.colorScheme.onSurface)

    // 当前月默认选中「今天」：一进来就看到「今天花了多少」，
    // 也顺带把「这张图能点」这件事暴露出来，不用靠副标题文案去解释。
    var selectedIndex by remember(trendData, year, month, selectedTab) {
        mutableStateOf(resolveTodayIndex(trendData, year, month))
    }

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
            val selectedPoint = trendData.getOrNull(selectedIndex)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Chart.canvasHeight)
                    .semantics {
                        contentDescription = buildString {
                            append("消费趋势折线图，共 ${trendData.size} 天")
                            selectedPoint?.let {
                                append("；当前选中 ${formatAxisDate(it.date)}，")
                                append(AmountFormatter.toYuanDisplay(it.amount))
                            }
                            append("。可点击或拖动查看某一天")
                        }
                    }
                    .pointerInput(trendData, selectedTab) {
                        // 点按 + 横向拖动统一用一个手势循环处理：
                        // 拆成 detectTapGestures + detectDragGestures 两个 pointerInput
                        // 会让两者抢同一个 down 事件，tap 在拖动后偶发失效。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val count = trendData.size
                            val width = size.width.toFloat()
                            selectedIndex = trendIndexForX(down.position.x, width, count)
                            // 持续跟随：按住左右滑就能像刷温度计一样扫过整个月
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                selectedIndex = trendIndexForX(change.position.x, width, count)
                                change.consume()
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                // 左留白给 Y 轴标签，底部留白给 X 轴标签。
                val leftPad = TREND_LEFT_PAD
                val bottomPad = TREND_BOTTOM_PAD
                val topPad = TREND_TOP_PAD
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
                // 画在选中态之前：用户选中别的日子时两个标记都能看见；
                // 选中的就是今天时，放大的选中点自然盖住它。
                if (todayIndex in points.indices) {
                    val tp = points[todayIndex]
                    drawCircle(color = lineColor.copy(alpha = 0.18f), radius = 16f, center = tp)
                    drawCircle(color = lineColor.copy(alpha = 0.32f), radius = 9f, center = tp)
                    drawCircle(color = lineColor, radius = 5f, center = tp)
                }

                // ── 选中态：竖直虚线 + 高亮点 + 顶部药丸 ──
                val selIdx = selectedIndex
                if (selIdx in points.indices) {
                    val sp = points[selIdx]
                    // 竖直虚线把 x 位置投影到整张图上，避免「高亮点在哪」和「那天是哪天」脱节
                    drawLine(
                        color = lineColor.copy(alpha = 0.45f),
                        start = Offset(sp.x, topPad),
                        end = Offset(sp.x, topPad + plotH),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    )
                    drawCircle(color = lineColor.copy(alpha = 0.14f), radius = 20f, center = sp)
                    drawCircle(color = lineColor.copy(alpha = 0.30f), radius = 11f, center = sp)
                    drawCircle(color = lineColor, radius = 6f, center = sp)

                    val point = trendData[selIdx]
                    val tip = textMeasurer.measure(
                        "${formatAxisDate(point.date)}  ${AmountFormatter.toYuanDisplay(point.amount)}",
                        tooltipTextStyle,
                    )
                    val pillW = tip.size.width + TOOLTIP_PAD_H * 2
                    val pillH = tip.size.height + TOOLTIP_PAD_V * 2
                    // 药丸跟随选中点但 x 方向夹在绘图区内：靠边的两天不会飘出画布
                    val pillX = (sp.x - pillW / 2f)
                        .coerceIn(leftPad, (w - pillW).coerceAtLeast(leftPad))
                    val pillY = topPad + 4f
                    drawRoundRect(
                        color = tooltipBg,
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(pillH / 2f),
                    )
                    drawText(
                        textLayoutResult = tip,
                        topLeft = Offset(pillX + TOOLTIP_PAD_H, pillY + TOOLTIP_PAD_V),
                    )
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
