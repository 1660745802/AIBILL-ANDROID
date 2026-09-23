package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.CategoryAvatar
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens

/**
 * 分类占比环形图（自绘 Canvas）。
 * 数据为空时显示提示。
 */
@Composable
fun CategoryDonutChart(
    categories: List<CategoryStat>,
    selectedTab: String,
    modifier: Modifier = Modifier,
) {
    val baseColor = if (selectedTab == "expense") ExpenseColor else IncomeColor

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
            Text(
                text = if (selectedTab == "expense") "支出构成" else "收入构成",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(Tokens.Spacing.md))
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
                                    alpha = 1f - idx * 0.12f.coerceAtMost(0.7f),
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
                    Spacer(modifier = Modifier.width(Tokens.Spacing.lg))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
                    ) {
                        categories.take(5).forEachIndexed { idx, cat ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = baseColor.copy(alpha = 1f - idx * 0.12f.coerceAtMost(0.7f)),
                                    modifier = Modifier.size(10.dp),
                                ) {}
                                Spacer(modifier = Modifier.width(Tokens.Spacing.xs + Tokens.Spacing.xs))
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

/**
 * 分类排行条目：图标 + 名称 + 进度条 + 金额。
 */
@Composable
fun CategoryStatItem(
    category: CategoryStat,
    selectedTab: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progressColor = if (selectedTab == "expense") ExpenseColor else IncomeColor

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.low),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryAvatar(
                icon = category.categoryIcon,
                modifier = Modifier.size(Tokens.Avatar.md),
                iconSize = 18,
            )
            Spacer(modifier = Modifier.width(Tokens.Spacing.md + Tokens.Spacing.xs))
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
                Spacer(modifier = Modifier.height(Tokens.Spacing.xs + Tokens.Spacing.xs))
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
            Spacer(modifier = Modifier.width(Tokens.Spacing.md + Tokens.Spacing.xs))
            AmountText(
                amount = category.amount,
                color = progressColor,
                style = MaterialTheme.typography.bodyMedium,
                showSign = false,
            )
        }
    }
}
