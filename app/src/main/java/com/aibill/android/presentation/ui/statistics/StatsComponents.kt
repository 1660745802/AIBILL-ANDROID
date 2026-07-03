package com.aibill.android.presentation.ui.statistics

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.utils.toYuanDisplay

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (trendData.isEmpty()) {
                Text(
                    text = "暂无趋势数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📈 趋势图（${trendData.size}天数据）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "待接入 Vico 图表库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
