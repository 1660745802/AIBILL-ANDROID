package com.aibill.android.presentation.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aibill.android.presentation.theme.SecondaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.ui.statistics.components.CategoryDonutChart
import com.aibill.android.presentation.ui.statistics.components.CategoryStatItem
import com.aibill.android.presentation.ui.statistics.components.IncomeExpenseCompareBar
import com.aibill.android.presentation.ui.statistics.components.MonthSelector
import com.aibill.android.presentation.ui.statistics.components.StatsTabRow
import com.aibill.android.presentation.ui.statistics.components.SummaryCard
import com.aibill.android.presentation.ui.statistics.components.TrendChartPlaceholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateToCategoryTransactions: (Int, String, Int, Int) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从其他页面返回时刷新统计（记账后自动反映到本月）
    // PR 优化：LifecycleResumeEffect 替代 DisposableEffect+LifecycleEventObserver。
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleResumeEffect(lifecycleOwner, lifecycleOwner) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
            MonthSelector(
                year = state.year,
                month = state.month,
                onPrevious = { viewModel.onMonthChanged(-1) },
                onNext = { viewModel.onMonthChanged(1) },
                onJumpToCurrent = { viewModel.onJumpToCurrentMonth() },
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatsTabRow(
                selectedTab = state.selectedTab,
                onTabChanged = { viewModel.onTabChanged(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

        when {
            state.isLoading && state.summary == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Tokens.Avatar.md),
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
            // PR #56：空状态改为「当月无任何数据时」才显示
            state.isLoading.not() && (state.summary == null ||
                (state.summary?.expense == 0 && state.summary?.income == 0 && state.categoryStats.isEmpty())) -> {
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
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
                ) {
                    item(key = "summary") {
                        SummaryCard(
                            summary = state.summary,
                            selectedTab = state.selectedTab,
                            daysInPeriod = if (state.year == java.time.LocalDate.now().year && state.month == java.time.LocalDate.now().monthValue) {
                                java.time.LocalDate.now().dayOfMonth
                            } else {
                                java.time.YearMonth.of(state.year, state.month).lengthOfMonth()
                            },
                        )
                    }
                    item(key = "compare_bar") {
                        IncomeExpenseCompareBar(
                            expense = state.summary?.expense ?: 0,
                            income = state.summary?.income ?: 0,
                        )
                    }
                    item(key = "trend") {
                        TrendChartPlaceholder(
                            trendData = state.trendData,
                            selectedTab = state.selectedTab,
                        )
                    }
                    if (state.categoryStats.isNotEmpty()) {
                        item(key = "donut") {
                            CategoryDonutChart(
                                categories = state.categoryStats,
                                selectedTab = state.selectedTab,
                            )
                        }
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
                        key = { "${it.categoryId}:${it.categoryName}" },
                    ) { category ->
                        CategoryStatItem(
                            category = category,
                            selectedTab = state.selectedTab,
                            onClick = {
                                category.categoryId?.let { onNavigateToCategoryTransactions(it, state.selectedTab, state.year, state.month) }
                            },
                        )
                    }

                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}
