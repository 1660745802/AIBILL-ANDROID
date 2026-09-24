package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import com.aibill.android.domain.model.Transaction
import com.aibill.android.presentation.components.AppendError
import com.aibill.android.presentation.components.AppendLoading
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.LoadingState
import com.aibill.android.presentation.theme.Tokens

/**
 * Paging 3 流水列表渲染（带按日分组）。
 *
 * - 不再使用 Map<String, List<Transaction>> 内存分组
 * - 不再使用 derivedStateOf{ shouldLoadMore } + 手写 loadMore()
 * - filter 变化由 Pager 自动 invalidate 重构
 * - 滚动位置由 Paging + LazyListState 自动恢复
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsPagingList(
    pagingItems: LazyPagingItems<Transaction>,
    listState: LazyListState,
    onDelete: (Int) -> Unit,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshState = pagingItems.loadState.refresh
    val isEmpty = pagingItems.itemCount == 0 && refreshState is LoadState.NotLoading

    if (isEmpty) {
        EmptyState(
            emoji = "📭",
            title = "暂无流水记录",
            subtitle = "去首页记一笔吧",
            modifier = modifier,
        )
        return
    }

    if (pagingItems.itemCount == 0 && refreshState is LoadState.Loading) {
        LoadingState(modifier = modifier)
        return
    }

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
        modifier = modifier.fillMaxSize(),
    ) {
        groupBoundaries.forEach { groupStart ->
            val groupDate = pagingItems.peek(groupStart)?.date ?: return@forEach
            val endExclusive = run {
                var idx = groupStart + 1
                while (idx < pagingItems.itemCount) {
                    if (pagingItems.peek(idx)?.date != groupDate) break
                    idx++
                }
                idx
            }

            item(key = "header_$groupDate", contentType = "header") {
                com.aibill.android.presentation.ui.transactions.DateHeader(
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
                com.aibill.android.presentation.ui.transactions.TransactionItem(
                    transaction = transaction,
                    onDelete = onDelete,
                    onClick = { transaction.id?.let { onItemClick(it) } },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp, end = Tokens.Spacing.xl),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                )
            }
        }

        if (pagingItems.loadState.append is LoadState.Loading) {
            item(key = "append_loading", contentType = "loading") {
                AppendLoading()
            }
        }

        val appendError = (pagingItems.loadState.append as? LoadState.Error)
        if (appendError != null) {
            item(key = "append_error", contentType = "error") {
                AppendError(onRetry = { pagingItems.retry() })
            }
        }
    }
}


