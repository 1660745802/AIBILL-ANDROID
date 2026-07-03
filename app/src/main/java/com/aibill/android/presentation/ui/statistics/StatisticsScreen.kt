package com.aibill.android.presentation.ui.statistics

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.SecondaryButton

@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        MonthSelector(
            year = state.year,
            month = state.month,
            onPrevious = { viewModel.onMonthChanged(-1) },
            onNext = { viewModel.onMonthChanged(1) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        StatsTabRow(
            selectedTab = state.selectedTab,
            onTabChanged = { viewModel.onTabChanged(it) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("😥", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = state.error ?: "加载失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        SecondaryButton(
                            text = "重试",
                            onClick = { viewModel.refresh() },
                        )
                    }
                }
            }
            state.categoryStats.isEmpty() && state.summary == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📊", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "暂无统计数据",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "记几笔账后这里会有精彩的统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "summary") {
                        SummaryCard(
                            summary = state.summary,
                            selectedTab = state.selectedTab,
                        )
                    }
                    item(key = "trend") {
                        TrendChartPlaceholder(
                            trendData = state.trendData,
                            selectedTab = state.selectedTab,
                        )
                    }
                    if (state.categoryStats.isNotEmpty()) {
                        item(key = "rank_title") {
                            Text(
                                text = "分类排行",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    items(
                        items = state.categoryStats,
                        key = { it.categoryId },
                    ) { category ->
                        CategoryStatItem(
                            category = category,
                            selectedTab = state.selectedTab,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上个月",
                modifier = Modifier.size(20.dp),
            )
        }
        SecondaryButton(
            text = "${year}年${month}月",
            onClick = {},
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下个月",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StatsTabRow(
    selectedTab: String,
    onTabChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf("expense" to "支出", "income" to "收入")
    val selectedIndex = if (selectedTab == "expense") 0 else 1
    val indicatorColor = if (selectedTab == "expense") ExpenseColor else IncomeColor

    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = indicatorColor,
            )
        },
        divider = {},
    ) {
        tabs.forEachIndexed { index, (key, label) ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onTabChanged(key) },
                text = {
                    Text(
                        text = label,
                        fontWeight = if (selectedIndex == index) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (selectedIndex == index) {
                            if (key == "expense") ExpenseColor else IncomeColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
    }
}
