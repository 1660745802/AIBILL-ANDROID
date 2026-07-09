package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.domain.model.Transaction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onNavigateToDetail: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 3 && uiState.hasMore && !uiState.isLoading
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is TransactionsViewModel.UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is TransactionsViewModel.UiEvent.ShowDeleteUndo -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "已删除",
                        actionLabel = "撤销",
                        withDismissAction = true,
                    )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete()
                    }
                }
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // PR #27：搜索框 + 类型过滤 chip（PRD §5.2.2 多维度筛选）
                SearchInputBar(
                    keyword = uiState.searchKeyword,
                    onKeywordChanged = viewModel::onSearchChanged,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 6.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.filterType == "all",
                    onClick = { viewModel.onFilterTypeChanged("all") },
                    label = { Text("全部") },
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = uiState.filterType == "expense",
                    onClick = { viewModel.onFilterTypeChanged("expense") },
                    label = { Text("支出") },
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = uiState.filterType == "income",
                    onClick = { viewModel.onFilterTypeChanged("income") },
                    label = { Text("收入") },
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.loadTransactions(refresh = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoading && uiState.transactions.isEmpty() -> {
                        LoadingContent()
                    }
                    uiState.transactions.isEmpty() && !uiState.isLoading -> {
                        if (uiState.searchKeyword.isNotBlank()) {
                            SearchEmptyContent(keyword = uiState.searchKeyword)
                        } else {
                            EmptyContent()
                        }
                    }
                    else -> {
                        TransactionList(
                            groupedTransactions = uiState.transactions,
                            hasMore = uiState.hasMore,
                            listState = listState,
                            onDelete = viewModel::onDeleteTransaction,
                            onItemClick = { id -> onNavigateToDetail(id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputBar(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = keyword,
        onValueChange = onKeywordChanged,
        modifier = modifier,
        placeholder = {
            Text(
                "搜索流水记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionList(
    groupedTransactions: Map<String, List<Transaction>>,
    hasMore: Boolean,
    listState: LazyListState,
    onDelete: (Int) -> Unit,
    onItemClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        groupedTransactions.forEach { (date, transactions) ->
            stickyHeader(key = "header_$date") {
                DateHeader(date = date, transactions = transactions)
            }
            items(
                items = transactions,
                key = { "${it.id ?: ""}:${it.clientId}" },
            ) { transaction ->
                TransactionItem(
                    transaction = transaction,
                    onDelete = onDelete,
                    onClick = { transaction.id?.let { onItemClick(it) } },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp, end = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                )
            }
        }
        if (hasMore) {
            item(key = "loading_more") { LoadMoreIndicator() }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun SearchEmptyContent(keyword: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "🔍", style = MaterialTheme.typography.displayMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "没有找到「$keyword」相关的记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "试试换个关键词搜索",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "📭", style = MaterialTheme.typography.displayMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无流水记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去首页记一笔吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun LoadMoreIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
