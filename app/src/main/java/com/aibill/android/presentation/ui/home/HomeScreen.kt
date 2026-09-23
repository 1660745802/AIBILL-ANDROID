package com.aibill.android.presentation.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.components.AmountFormat
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.GradientSummaryCard
import com.aibill.android.presentation.components.LoadingState
import com.aibill.android.presentation.components.Metric
import com.aibill.android.presentation.components.TransactionRow
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.WarningColor
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToNotification: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToDetail: (Int) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            val message = when (event) {
                is HomeViewModel.UiEvent.ShowToast -> event.message
                is HomeViewModel.UiEvent.ShowError -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // 返回首页时刷新数据（编辑/记账后自动更新今日流水 + 月度）
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleResumeEffect(lifecycleOwner, lifecycleOwner) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "${LocalDate.now().year} 年 ${LocalDate.now().monthValue} 月",
            ) {
                IconButton(onClick = onNavigateToNotification) {
                    if (uiState.pendingNotificationCount > 0) {
                        BadgedBox(badge = {
                            Badge { Text("${uiState.pendingNotificationCount}") }
                        }) {
                            Icon(Icons.Default.Notifications, contentDescription = "通知中心")
                        }
                    } else {
                        Icon(Icons.Default.Notifications, contentDescription = "通知中心")
                    }
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isLoading && uiState.todayTransactions.isEmpty() && !uiState.isRefreshing) {
                LoadingState()
            } else {
                HomeContent(
                    uiState = uiState,
                    onHeaderClick = onNavigateToStatistics,
                    onSyncClick = viewModel::triggerSync,
                    onItemClick = { id -> onNavigateToDetail(id) },
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeViewModel.HomeUiState,
    onHeaderClick: () -> Unit,
    onSyncClick: () -> Unit,
    onItemClick: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Tokens.Spacing.screenHorizontal,
            end = Tokens.Spacing.screenHorizontal,
            top = Tokens.Spacing.xl,
            bottom = Tokens.Spacing.screenBottomWithFab,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.listItemSpacing),
    ) {
        item(key = "header") {
            GradientSummaryCard(
                label = "本月支出",
                amountFen = uiState.monthlyExpense,
                modifier = Modifier.clickable { onHeaderClick() },
                periodLabel = "${LocalDate.now().monthValue}月 · 剩${LocalDate.now().lengthOfMonth() - LocalDate.now().dayOfMonth}天",
                secondaryMetrics = buildList {
                    val daysPassed = LocalDate.now().dayOfMonth
                    val dailyAvg = if (daysPassed > 0) uiState.monthlyExpense.toFloat() / daysPassed / 100f else 0f
                    add(Metric("日均", "¥${"%.0f".format(dailyAvg)}"))
                    if (uiState.monthlyIncome > 0) {
                        add(Metric("收入", AmountFormat.toYuanDisplay(uiState.monthlyIncome)))
                    }
                },
            )
        }

        if (uiState.pendingSyncCount > 0) {
            item(key = "pending_sync") {
                PendingSyncChip(
                    count = uiState.pendingSyncCount,
                    isSyncing = uiState.isSyncing,
                    onSyncClick = onSyncClick,
                )
            }
        }

        item(key = "today_title") {
            TodayTitleRow(
                autoCount = remember(uiState.todayTransactions) {
                    uiState.todayTransactions.count {
                        it.source == TransactionSource.APP_NOTIFICATION
                    }
                },
                todayExpenseFen = remember(uiState.todayTransactions) {
                    uiState.todayTransactions
                        .filter { it.type == TransactionType.EXPENSE }
                        .sumOf { it.amount }
                },
            )
        }

        if (uiState.todayTransactions.isEmpty()) {
            item(key = "empty") {
                EmptyTodayCard()
            }
        } else {
            items(
                items = uiState.todayTransactions,
                key = { it.clientId },
            ) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    onClick = { transaction.id?.let { onItemClick(it) } },
                )
            }
        }
    }
}

@Composable
private fun TodayTitleRow(autoCount: Int, todayExpenseFen: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "今日流水",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (autoCount > 0) {
            Spacer(Modifier.width(Tokens.Spacing.sm))
            Text(
                text = "自动 $autoCount 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "今日支出 ${AmountFormat.toYuanDisplay(todayExpenseFen)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyTodayCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Tokens.Spacing.huge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "😌", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(Tokens.Spacing.md))
            Text(
                text = "今天还没有消费记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Tokens.Spacing.xs))
            Text(
                text = "通知监听运行中，支付后自动记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PendingSyncChip(count: Int, isSyncing: Boolean, onSyncClick: () -> Unit) {
    SuggestionChip(
        onClick = onSyncClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = WarningColor,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "同步中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningColor,
                    )
                } else {
                    Text(
                        text = "⚠️ $count 笔待同步",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningColor,
                    )
                }
            }
        },
        enabled = !isSyncing,
        border = SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = WarningColor.copy(alpha = 0.5f),
        ),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = WarningColor.copy(alpha = 0.1f),
        ),
    )
}
