package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.aibill.android.domain.model.Transaction

/**
 * 流水分组条目（PR C2：Paging 3 渲染层）
 *
 * PagingData<Transaction> 是扁平流，按需切组：
 * - HEADER + TRANSACTION 交替
 * - 相邻 TRANSACTION 同 date 时复用前一个 HEADER
 */
private sealed class TransactionRow {
    data class Header(val date: String, val items: List<Transaction>) : TransactionRow()
    data class Item(val transaction: Transaction) : TransactionRow()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    initialCategoryId: Int? = null,
    initialType: String? = null,
    initialStartDate: String? = null,
    initialEndDate: String? = null,
    onNavigateToDetail: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    // 如果有初始分类筛选，设置到 ViewModel
    LaunchedEffect(initialCategoryId, initialType, initialStartDate) {
        if (initialStartDate != null) {
            viewModel.setDateRange(initialStartDate, initialEndDate)
        }
        if (initialCategoryId != null) {
            viewModel.setCategoryFilter(initialCategoryId)
        }
        if (initialType != null) {
            viewModel.onFilterTypeChanged(initialType)
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // PR C2：Paging 3 直接渲染，filter 变化时 Pager 自动重建
    val pagingItems = viewModel.transactionsPager.collectAsLazyPagingItems()

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
            // 日期范围选择（快捷chip + 自定义范围）
            var showDatePicker by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.filterDateLabel == "全部",
                    onClick = { viewModel.clearDateFilter() },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = uiState.filterDateLabel == "本月",
                    onClick = { viewModel.onJumpToCurrentMonth() },
                    label = { Text("本月") },
                )
                FilterChip(
                    selected = uiState.filterDateLabel == "上月",
                    onClick = {
                        val ym = java.time.YearMonth.now().minusMonths(1)
                        viewModel.onDateRangeSelected(
                            ym.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            ym.atEndOfMonth().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        )
                    },
                    label = { Text("上月") },
                )
                FilterChip(
                    selected = uiState.filterDateLabel != "全部" && uiState.filterDateLabel != "本月" && uiState.filterDateLabel != "上月",
                    onClick = { showDatePicker = true },
                    label = {
                        Text(
                            if (uiState.filterDateLabel != "全部" && uiState.filterDateLabel != "本月" && uiState.filterDateLabel != "上月")
                                uiState.filterDateLabel
                            else "自定义"
                        )
                    },
                )
            }

            // DateRangePicker Dialog
            if (showDatePicker) {
                val dateRangePickerState = rememberDateRangePickerState()
                androidx.compose.material3.DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                val start = dateRangePickerState.selectedStartDateMillis
                                val end = dateRangePickerState.selectedEndDateMillis
                                if (start != null && end != null) {
                                    viewModel.onDateRangeSelected(start, end)
                                }
                                showDatePicker = false
                            },
                        ) { Text("确定") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                    },
                ) {
                    androidx.compose.material3.DateRangePicker(
                        state = dateRangePickerState,
                        modifier = Modifier.height(500.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.filterType == "all",
                    onClick = { viewModel.onFilterTypeChanged("all") },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = uiState.filterType == "expense",
                    onClick = { viewModel.onFilterTypeChanged("expense") },
                    label = { Text("支出") },
                )
                FilterChip(
                    selected = uiState.filterType == "income",
                    onClick = { viewModel.onFilterTypeChanged("income") },
                    label = { Text("收入") },
                )
                if (uiState.categories.isNotEmpty()) {
                    CategoryFilterDropdown(
                        categories = uiState.categories,
                        selectedCategoryId = uiState.filterCategoryId,
                        onCategorySelected = { viewModel.setCategoryFilter(it) },
                    )
                }
            }

            // 标签筛选行
            if (uiState.availableTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uiState.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag in uiState.filterTags,
                            onClick = {
                                viewModel.setTagFilter(tag)
                            },
                            label = { Text("#$tag") },
                        )
                    }
                }
            }

            // 筛选合计（只在选了日期时显示）
            if (uiState.filterStartDate != null) {
                val expenseTotal = uiState.periodExpense
                val incomeTotal = uiState.periodIncome
                if (expenseTotal > 0 || incomeTotal > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (expenseTotal > 0) {
                            Text(
                                text = "支出 ¥${"%.2f".format(expenseTotal / 100.0)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = com.aibill.android.presentation.theme.ExpenseColor,
                            )
                        }
                        if (incomeTotal > 0) {
                            Text(
                                text = "收入 ¥${"%.2f".format(incomeTotal / 100.0)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = com.aibill.android.presentation.theme.IncomeColor,
                            )
                        }
                    }
                }
            }

            // PR C2：Paging 3 直接渲染 + 自动预加载（LazyColumn 滚动到底自动 load next page）
            PullToRefreshBox(
                isRefreshing = pagingItems.loadState.refresh is LoadState.Loading,
                onRefresh = { pagingItems.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                PagingTransactionList(
                    pagingItems = pagingItems,
                    listState = listState,
                    onDelete = viewModel::onDeleteTransaction,
                    onItemClick = { id -> onNavigateToDetail(id) },
                    searchKeyword = uiState.searchKeyword,
                )
            }
        }
    }
}

/**
 * PR C2：流水列表渲染（LazyPagingItems）
 *
 * - 不再有 Map<String, List<Transaction>> 内存分组
 * - 不再有 derivedStateOf{ shouldLoadMore } + 手写 loadMore()
 * - 不再有 isLoading/isRefreshing 状态管理（pagingItems.loadState 提供）
 * - 滚动位置由 Paging + LazyListState 自动恢复
 * - filter 变化时 Pager 自动 invalidate 重构
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagingTransactionList(
    pagingItems: LazyPagingItems<Transaction>,
    listState: LazyListState,
    onDelete: (Int) -> Unit,
    onItemClick: (Int) -> Unit = {},
    searchKeyword: String = "",
) {
    // 边界：初始加载 + 空列表 → 显示空状态
    val refreshState = pagingItems.loadState.refresh
    val isEmpty = pagingItems.itemCount == 0 && refreshState is LoadState.NotLoading

    if (isEmpty) {
        if (searchKeyword.isNotBlank()) {
            SearchEmptyContent(keyword = searchKeyword)
        } else {
            EmptyContent()
        }
        return
    }

    // 初始 loading：还没拿到任何 item 时显示居中 loading
    if (pagingItems.itemCount == 0 && refreshState is LoadState.Loading) {
        LoadingContent()
        return
    }

    // PR C2：缓存 group 边界，按 itemCount 变化重算（避免每次重组都扫描）
    val groupBoundaries = remember(pagingItems.itemCount) {
        val itemCount = pagingItems.itemCount
        val boundaries = mutableListOf<Int>()
        var lastDate: String? = null
        for (i in 0 until itemCount) {
            val tx = pagingItems.peek(i)
            if (tx != null && tx.date != lastDate) {
                boundaries.add(i)
                lastDate = tx.date
            }
        }
        boundaries
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        // 渲染：每个 group 第一项前插入 header
        groupBoundaries.forEach { groupStart ->
            val groupDate = pagingItems.peek(groupStart)?.date ?: return@forEach
            // 计算该 group 包含的 item 索引范围（仅 peek，不触发 load）
            val endExclusive = run {
                var idx = groupStart + 1
                while (idx < pagingItems.itemCount) {
                    if (pagingItems.peek(idx)?.date != groupDate) break
                    idx++
                }
                idx
            }

            item(key = "header_$groupDate", contentType = "header") {
                DateHeader(
                    date = groupDate,
                    transactions = (groupStart until endExclusive).mapNotNull { pagingItems.peek(it) },
                )
            }

            items(
                count = endExclusive - groupStart,
                key = { offset ->
                    val tx = pagingItems.peek(groupStart + offset)
                    "${tx?.id ?: ""}:${tx?.clientId ?: ""}"
                },
                contentType = { "transaction" },
            ) { offset ->
                val transaction = pagingItems.peek(groupStart + offset) ?: return@items
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

        // PR C2：append 加载更多指示器（取代旧 LoadMoreIndicator）
        if (pagingItems.loadState.append is LoadState.Loading) {
            item(key = "append_loading", contentType = "loading") {
                LoadMoreIndicator()
            }
        }

        // append 错误：显示重试
        val appendError = (pagingItems.loadState.append as? LoadState.Error)
        if (appendError != null) {
            item(key = "append_error", contentType = "error") {
                LoadMoreError(onRetry = { pagingItems.retry() })
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
private fun CategoryFilterDropdown(
    categories: List<com.aibill.android.domain.model.Category>,
    selectedCategoryId: Int?,
    onCategorySelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }
        ?.let { "${it.icon} ${it.name}" } ?: "分类"

    Box(modifier = modifier) {
        FilterChip(
            selected = selectedCategoryId != null,
            onClick = { expanded = true },
            label = { Text(selectedName) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            DropdownMenuItem(
                text = { Text("全部分类") },
                onClick = { onCategorySelected(null); expanded = false },
            )
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text("${cat.icon} ${cat.name}") },
                    onClick = { onCategorySelected(cat.id); expanded = false },
                )
            }
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

@Composable
private fun LoadMoreError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text("加载更多失败，点击重试")
        }
    }
}