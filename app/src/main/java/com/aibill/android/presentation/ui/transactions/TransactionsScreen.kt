package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.aibill.android.presentation.ui.transactions.components.TransactionsFiltersCallbacks
import com.aibill.android.presentation.ui.transactions.components.TransactionsFilters
import com.aibill.android.presentation.ui.transactions.components.TransactionsPagingList
import java.time.YearMonth

/**
 * 流水页。**已重构**：filter 行 / 列表 / 空状态拆为独立组件。
 *
 * - filter: [TransactionsFilters]
 * - list:   [TransactionsPagingList]
 * - 空状态/loading: 复用 presentation/components/States
 */
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
    val pagingItems = viewModel.transactionsPager.collectAsLazyPagingItems()

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is TransactionsViewModel.UiEvent.ShowToast ->
                    snackbarHostState.showSnackbar(event.message)
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
            TransactionsFilters(
                state = uiState,
                categories = uiState.categories,
                availableTags = uiState.availableTags,
                callbacks = TransactionsFiltersCallbacks(
                    onClearDate = viewModel::clearDateFilter,
                    onJumpToCurrentMonth = viewModel::onJumpToCurrentMonth,
                    onSelectLastMonth = {
                        val ym = YearMonth.now().minusMonths(1)
                        viewModel.onDateRangeSelected(
                            ym.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            ym.atEndOfMonth().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        )
                    },
                    onSelectCustomDate = viewModel::onDateRangeSelected,
                    onTypeChanged = viewModel::onFilterTypeChanged,
                    onCategoryChanged = viewModel::setCategoryFilter,
                    onTagToggled = viewModel::setTagFilter,
                ),
            )

            PullToRefreshBox(
                isRefreshing = pagingItems.loadState.refresh is LoadState.Loading,
                onRefresh = { pagingItems.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                TransactionsPagingList(
                    pagingItems = pagingItems,
                    listState = listState,
                    searchKeyword = uiState.searchKeyword,
                    onDelete = viewModel::onDeleteTransaction,
                    onItemClick = onNavigateToDetail,
                )
            }
        }
    }
}
