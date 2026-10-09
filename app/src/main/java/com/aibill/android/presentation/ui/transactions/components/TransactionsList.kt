package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.aibill.android.domain.model.Transaction
import com.aibill.android.presentation.components.AppendError
import com.aibill.android.presentation.components.AppendLoading
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.ErrorState
import com.aibill.android.presentation.components.LoadingState
import com.aibill.android.presentation.ui.transactions.DateHeader
import com.aibill.android.presentation.ui.transactions.TransactionItem
import com.aibill.android.presentation.theme.Tokens

/**
 * 按日分组（组合期从**同一份快照**计算，保证边界与 key 一致）。
 */
private class DayGroup(val date: String, val items: List<Transaction>)

/**
 * Paging 3 流水列表渲染（带按日分组 + 粘性日期头）。
 *
 * **PR #69 根因修复（闪退）—— 重写列表时必须守住的不变量**：
 * 之前分组边界用 `remember(pagingItems.itemCount)` 缓存、item key 在 key lambda
 * 中读 `pagingItems.peek()`，存在两个致命缺陷：
 * 1. 切换筛选后新旧数据 itemCount 相同（如都是 20）→ remember 命中旧边界 →
 *    新数据套旧边界 → 分组重叠 → LazyColumn item key 重复 → 崩溃
 * 2. key lambda 在布局阶段执行，与组合阶段读到的快照可能不一致（PagingData
 *    正在切换，peek 返回 null）→ 多个 item 的 key 都变成 ":" → 重复 → 崩溃
 *
 * 修复（本次重写继续保持）：组合期一次性捕获不可变快照 [txList]，分组与所有
 * key 都只基于该快照，key lambda 内绝不读 peek()。
 *
 * **UI 重设计**：
 * - 日期头改为 `stickyHeader`，滚动时始终能看到当前属于哪一天。
 * - 行间分隔线起点改用 [Tokens.List.dividerIndent]（76dp，对齐头像右缘），
 *   颜色 outlineVariant.copy(alpha=0.4f)，粗 0.5dp。
 * - 列表行放弃 swipe-to-delete，改为长按触发 [onLongPress] 弹出的操作菜单
 *   （菜单在 Screen 层以 ModalBottomSheet 承载，列表只负责上报被长按的交易）。
 * - 空/错/加载态走共享 `EmptyState` / `ErrorState` / `LoadingState` / `AppendError`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsPagingList(
    pagingItems: LazyPagingItems<Transaction>,
    listState: LazyListState,
    onItemClick: (Int) -> Unit,
    onLongPress: (Transaction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshState = pagingItems.loadState.refresh
    val isEmpty = pagingItems.itemCount == 0 && refreshState is LoadState.NotLoading

    if (pagingItems.itemCount == 0 && refreshState is LoadState.Error) {
        ErrorState(
            title = "没能加载流水",
            subtitle = "检查网络后重试，已记录的流水不会丢失",
            onAction = { pagingItems.retry() },
            modifier = modifier,
        )
        return
    }

    if (isEmpty) {
        EmptyState(
            icon = Icons.Outlined.ReceiptLong,
            title = "还没有流水记录",
            subtitle = "记下的每一笔都会按日期出现在这里，试着记下第一笔吧",
            modifier = modifier,
        )
        return
    }

    if (pagingItems.itemCount == 0 && refreshState is LoadState.Loading) {
        LoadingState(modifier = modifier)
        return
    }

    // ===== 关键修复：一次性捕获快照，禁止在 key lambda 中再读 peek() =====
    val txList: List<Transaction> = List(pagingItems.itemCount) { index ->
        pagingItems.peek(index)
    }.filterNotNull()

    val groups: List<DayGroup> = buildDayGroups(txList)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            // header key 带 groupIndex：即使数据未按日期排序（日期交错）也不会撞 key
            stickyHeader(key = "header_${groupIndex}_${group.date}", contentType = "header") {
                DateHeader(
                    date = group.date,
                    transactions = group.items,
                )
            }

            items(
                count = group.items.size,
                key = { offset -> itemKey(group.items[offset]) },
                contentType = { "transaction" },
            ) { offset ->
                val transaction = group.items[offset]
                TransactionItem(
                    transaction = transaction,
                    onClick = { transaction.id?.let { onItemClick(it) } },
                    onLongClick = { onLongPress(transaction) },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(
                        start = Tokens.List.dividerIndent,
                        end = Tokens.Spacing.screenHorizontal,
                    ),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = Tokens.Border.hairline,
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

/**
 * 按连续相同日期分组。组内 items 互不重叠 → item key 全局唯一；
 * 每条交易只属于一个组 → 不会出现同一 id 的 key 重复。
 */
private fun buildDayGroups(list: List<Transaction>): List<DayGroup> {
    if (list.isEmpty()) return emptyList()
    val groups = mutableListOf<DayGroup>()
    val currentItems = mutableListOf(list.first())
    for (i in 1 until list.size) {
        val tx = list[i]
        if (tx.date == currentItems.last().date) {
            currentItems.add(tx)
        } else {
            groups.add(DayGroup(currentItems.first().date, currentItems.toList()))
            currentItems.clear()
            currentItems.add(tx)
        }
    }
    groups.add(DayGroup(currentItems.first().date, currentItems.toList()))
    return groups
}

/** 稳定 item key（同一交易始终同一 key；不同交易不会冲突） */
private fun itemKey(tx: Transaction): String = "${tx.id ?: ""}:${tx.clientId}"
