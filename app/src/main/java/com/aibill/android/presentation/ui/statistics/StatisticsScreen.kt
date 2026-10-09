package com.aibill.android.presentation.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.ErrorState
import com.aibill.android.presentation.components.LoadingState
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.ui.statistics.components.CategoryDonutChart
import com.aibill.android.presentation.ui.statistics.components.CategoryRankList
import com.aibill.android.presentation.ui.statistics.components.IncomeExpenseCompareBar
import com.aibill.android.presentation.ui.statistics.components.StatsHeader
import com.aibill.android.presentation.ui.statistics.components.SummaryCard
import com.aibill.android.presentation.ui.statistics.components.TrendChart
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateToCategoryTransactions: (Int, String, Int, Int) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从其他页面返回时刷新统计（记账后自动反映到本月）
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleResumeEffect(lifecycleOwner, lifecycleOwner) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 粘性头部：月份步进器 + 收支分段控件（同一行）
        StatsHeader(
            year = state.year,
            month = state.month,
            selectedTab = state.selectedTab,
            onPreviousMonth = { viewModel.onMonthChanged(-1) },
            onNextMonth = { viewModel.onMonthChanged(1) },
            onJumpToCurrent = { viewModel.onJumpToCurrentMonth() },
            onTabChanged = { viewModel.onTabChanged(it) },
        )

        val isEmpty = state.summary == null ||
            (state.summary?.expense == 0 && state.summary?.income == 0 && state.categoryStats.isEmpty())

        when {
            state.isLoading && state.summary == null -> {
                LoadingState(modifier = Modifier.fillMaxSize())
            }

            state.error != null -> {
                ErrorState(
                    title = "统计加载失败",
                    subtitle = state.error ?: "请检查网络后重试",
                    icon = Icons.Outlined.CloudOff,
                    onAction = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // PR #56：当月无任何数据时才显示空态
            !state.isLoading && isEmpty -> {
                EmptyState(
                    title = "这个月还没有记账",
                    subtitle = "去记一笔，统计会自动生成",
                    icon = Icons.Outlined.BarChart,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                StatsContent(
                    state = state,
                    onNavigateToCategoryTransactions = onNavigateToCategoryTransactions,
                )
            }
        }
    }
}

@Composable
private fun StatsContent(
    state: StatisticsViewModel.StatsUiState,
    onNavigateToCategoryTransactions: (Int, String, Int, Int) -> Unit,
) {
    val now = LocalDate.now()
    val isCurrentMonth = state.year == now.year && state.month == now.monthValue
    val daysInPeriod = if (isCurrentMonth) {
        now.dayOfMonth
    } else {
        YearMonth.of(state.year, state.month).lengthOfMonth()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Tokens.Spacing.screenHorizontal,
            end = Tokens.Spacing.screenHorizontal,
            top = Tokens.Spacing.lg,
            bottom = Tokens.Spacing.screenBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
    ) {
        // 汇总卡 + 紧贴其下的收支对比条（视觉上同属一组）
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                SummaryCard(
                    summary = state.summary,
                    selectedTab = state.selectedTab,
                    daysInPeriod = daysInPeriod,
                    onClick = null,
                )
                IncomeExpenseCompareBar(
                    expense = state.summary?.expense ?: 0,
                    income = state.summary?.income ?: 0,
                )
            }
        }

        item(key = "trend") {
            TrendChart(
                trendData = state.trendData,
                selectedTab = state.selectedTab,
                year = state.year,
                month = state.month,
            )
        }

        if (state.categoryStats.isNotEmpty()) {
            item(key = "donut") {
                CategoryDonutChart(
                    categories = state.categoryStats,
                    selectedTab = state.selectedTab,
                    onCategoryClick = { categoryId ->
                        onNavigateToCategoryTransactions(
                            categoryId,
                            state.selectedTab,
                            state.year,
                            state.month,
                        )
                    },
                )
            }

            item(key = "rank") {
                CategoryRankList(
                    categories = state.categoryStats,
                    selectedTab = state.selectedTab,
                    onCategoryClick = { categoryId ->
                        onNavigateToCategoryTransactions(
                            categoryId,
                            state.selectedTab,
                            state.year,
                            state.month,
                        )
                    },
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(Tokens.Spacing.sm)) }
    }
}
