package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.AppCard
import com.aibill.android.presentation.components.CategoryAvatar
import com.aibill.android.presentation.components.GroupedDivider
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import androidx.compose.ui.unit.dp

/** 环形图最多展示的扇区数，其余聚合进「其他」。 */
private const val MAX_SEGMENTS = 6

/** 一个待绘制的扇区（可能是真实分类，也可能是聚合出的「其他」）。 */
private data class DonutSegment(
    val categoryId: Int?,
    val name: String,
    val icon: String,
    val amount: Int,
    val percent: Double,
    val color: Color,
)

/**
 * 构建有序色阶的扇区列表：
 * - 前 [MAX_SEGMENTS]-1 个分类各占一段，颜色从 [base] 向更浅 alpha 递减（步长 0.14）。
 * - 其余分类聚合为「其他」，用最浅一档。
 */
private fun buildSegments(categories: List<CategoryStat>, base: Color): List<DonutSegment> {
    if (categories.isEmpty()) return emptyList()
    val step = 0.14f
    fun shade(idx: Int) = base.copy(alpha = (1f - idx * step).coerceIn(0.3f, 1f))

    return if (categories.size <= MAX_SEGMENTS) {
        categories.mapIndexed { idx, c ->
            DonutSegment(c.categoryId, c.categoryName, c.categoryIcon, c.amount, c.percent, shade(idx))
        }
    } else {
        val head = categories.take(MAX_SEGMENTS - 1)
        val tail = categories.drop(MAX_SEGMENTS - 1)
        val headSegs = head.mapIndexed { idx, c ->
            DonutSegment(c.categoryId, c.categoryName, c.categoryIcon, c.amount, c.percent, shade(idx))
        }
        val otherAmount = tail.sumOf { it.amount }
        val otherPercent = tail.sumOf { it.percent }
        headSegs + DonutSegment(
            categoryId = null,
            name = "其他",
            icon = "📦",
            amount = otherAmount,
            percent = otherPercent,
            color = shade(MAX_SEGMENTS - 1),
        )
    }
}

/**
 * 分类占比环形图（自绘 Canvas）。
 *
 * 要点：有序色阶（最多 6 段，其余归「其他」）、扇区间隙用卡片背景色填出而非减角度、
 * 环心显示总额 + 「本月」、右侧图例最多显示 6 行且可点击切到该分类流水。
 */
@Composable
fun CategoryDonutChart(
    categories: List<CategoryStat>,
    selectedTab: String,
    onCategoryClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val base = if (selectedTab == "expense") {
        MaterialTheme.semantic.expense
    } else {
        MaterialTheme.semantic.income
    }
    val gapColor = MaterialTheme.colorScheme.surfaceContainerLow
    val trackColor = MaterialTheme.semantic.chartGrid
    val segments = buildSegments(categories, base)
    val total = segments.sumOf { it.amount }

    AppCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SectionHeader(title = if (selectedTab == "expense") "支出构成" else "收入构成")
        Spacer(Modifier.height(Tokens.Spacing.md))

        if (segments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Chart.canvasHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无分类数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(Tokens.Avatar.hero.times(2.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val strokeWidth = 26f
                        val arcSize = Size(w - strokeWidth, h - strokeWidth)
                        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                        val safeTotal = total.coerceAtLeast(1)
                        // 用背景色描一圈当作「空槽」/ 间隙底色。
                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth),
                        )
                        var startAngle = -90f
                        segments.forEach { seg ->
                            val sweep = (seg.amount.toFloat() / safeTotal) * 360f
                            drawArc(
                                color = seg.color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth),
                            )
                            // 用卡片背景色画一条细缝分隔相邻扇区（深色模式也不会露黑缝）。
                            if (segments.size > 1) {
                                drawArc(
                                    color = gapColor,
                                    startAngle = startAngle,
                                    sweepAngle = 1.5f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth),
                                )
                            }
                            startAngle += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = AmountFormatter.toCompactDisplay(total),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = "本月",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(Tokens.Spacing.lg))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                ) {
                    segments.take(6).forEach { seg ->
                        LegendRow(
                            color = seg.color,
                            name = seg.name,
                            percent = seg.percent,
                            onClick = seg.categoryId?.let { id -> { onCategoryClick(id) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    name: String,
    percent: Double,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.xs))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Tokens.Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Tokens.Spacing.sm + Tokens.Spacing.xxs)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = "${"%.1f".format(percent)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 分类排行列表：整组行装进一张 [AppCard]，行间发丝线分隔（不再每条一个卡）。
 *
 * 行内容：排名（Top 3 数字徽章）+ 分类头像 + 名称 + 占比 + 金额 + 细进度条。
 * 进度条颜色取该分类在色阶中的颜色，与环形图一致。整行可点击跳该分类流水。
 */
@Composable
fun CategoryRankList(
    categories: List<CategoryStat>,
    selectedTab: String,
    onCategoryClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    val base = if (selectedTab == "expense") {
        MaterialTheme.semantic.expense
    } else {
        MaterialTheme.semantic.income
    }
    val step = 0.14f
    val maxPercent = categories.maxOf { it.percent }.coerceAtLeast(0.0001)

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "分类排行", subtitle = "共 ${categories.size} 项")
        Spacer(Modifier.height(Tokens.Spacing.sm))
        // 无内边距：每行自带内边距
        AppCard(contentPadding = PaddingValues(0.dp)) {
            categories.forEachIndexed { idx, cat ->
                val rowColor = base.copy(alpha = (1f - idx * step).coerceIn(0.3f, 1f))
                CategoryRankRow(
                    rank = idx + 1,
                    category = cat,
                    color = rowColor,
                    fillFraction = (cat.percent / maxPercent).toFloat().coerceIn(0f, 1f),
                    onClick = { onCategoryClick(cat.categoryId) },
                )
                if (idx != categories.lastIndex) {
                    GroupedDivider()
                }
            }
        }
    }
}

@Composable
private fun CategoryRankRow(
    rank: Int,
    category: CategoryStat,
    color: Color,
    fillFraction: Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 排名徽章：Top 3 显示数字徽章，其余留空占位（序号在排行里确有意义）。
        Box(
            modifier = Modifier.size(Tokens.Avatar.sm),
            contentAlignment = Alignment.Center,
        ) {
            if (rank <= 3) {
                Box(
                    modifier = Modifier
                        .size(Tokens.IconSize.md)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
        }
        Spacer(Modifier.width(Tokens.Spacing.sm))
        CategoryAvatar(
            icon = category.categoryIcon,
            modifier = Modifier.size(Tokens.Avatar.md),
            iconSize = 18,
        )
        Spacer(Modifier.width(Tokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = category.categoryName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Tokens.Spacing.sm))
                Text(
                    text = "${"%.1f".format(category.percent)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(Tokens.Spacing.xs))
            LinearProgressIndicator(
                progress = { fillFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Chart.progressHeight)
                    .clip(RoundedCornerShape(Tokens.Radius.xs)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Spacer(Modifier.width(Tokens.Spacing.md))
        AmountText(
            amount = category.amount,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            showSign = false,
        )
    }
}
