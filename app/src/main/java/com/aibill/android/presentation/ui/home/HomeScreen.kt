package com.aibill.android.presentation.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import java.time.LocalDate
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.utils.toYuanDisplay
import kotlinx.coroutines.flow.collectLatest

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
            when (event) {
                is HomeViewModel.UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is HomeViewModel.UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // 从其他页面返回时刷新数据（编辑交易/记账后回到首页自动更新今日流水+月支出）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val now = LocalDate.now()
                    Text(
                        text = "${now.year} 年 ${now.monthValue} 月",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 20.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        MonthlyExpenseHeader(
                            amount = uiState.monthlyExpense,
                            income = uiState.monthlyIncome,
                            modifier = Modifier.clickable { onNavigateToStatistics() },
                        )
                    }

                    if (uiState.pendingSyncCount > 0) {
                        item(key = "pending_sync") {
                            PendingSyncChip(
                                count = uiState.pendingSyncCount,
                                isSyncing = uiState.isSyncing,
                                onSyncClick = viewModel::triggerSync,
                            )
                        }
                    }

                    item(key = "today_title") {
                        val autoCount = uiState.todayTransactions.count {
                            it.source == com.aibill.android.domain.model.TransactionSource.APP_NOTIFICATION
                        }
                        val todayExpenseTotal = uiState.todayTransactions
                            .filter { it.type == TransactionType.EXPENSE }
                            .sumOf { it.amount }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "今日流水",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (autoCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "自动 $autoCount 笔",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "今日支出 ${todayExpenseTotal.toYuanDisplay()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 流水条目（灰色背景占满剩余高度）
                    if (uiState.todayTransactions.isEmpty() && !uiState.isLoading) {
                        item(key = "empty") {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillParentMaxHeight(0.5f)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyTodayCard()
                            }
                        }
                    } else {
                        items(
                            items = uiState.todayTransactions,
                            key = { it.clientId },
                        ) { transaction ->
                            TransactionItem(
                                transaction = transaction,
                                onClick = { transaction.id?.let { onNavigateToDetail(it) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingSyncChip(
    count: Int,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amberColor = Color(0xFFF59E0B)

    androidx.compose.material3.SuggestionChip(
        onClick = onSyncClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = amberColor,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "同步中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = amberColor,
                    )
                } else {
                    Text(
                        text = "⚠\uFE0F ${count}笔待同步",
                        style = MaterialTheme.typography.labelMedium,
                        color = amberColor,
                    )
                }
            }
        },
        modifier = modifier,
        enabled = !isSyncing,
        border = androidx.compose.material3.SuggestionChipDefaults.suggestionChipBorder(
            enabled = true,
            borderColor = amberColor.copy(alpha = 0.5f),
        ),
        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
            containerColor = amberColor.copy(alpha = 0.1f),
        ),
    )
}
