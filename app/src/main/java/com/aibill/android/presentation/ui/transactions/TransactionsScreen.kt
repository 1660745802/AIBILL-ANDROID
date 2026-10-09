package com.aibill.android.presentation.ui.transactions

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.aibill.android.domain.model.Transaction
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.CategoryAvatar
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.ui.transactions.components.TransactionsFilters
import com.aibill.android.presentation.ui.transactions.components.TransactionsFiltersCallbacks
import com.aibill.android.presentation.ui.transactions.components.TransactionsPagingList
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 流水页。**重设计**：
 * - 真正补上 [AppTopBar]（标题「流水」）作为视觉锚点。从统计页带筛选跳入时
 *   （传入 initialCategoryId/type/startDate）显示返回箭头，走系统返回栈。
 * - 右侧「筛选」action：带生效数量圆点，点击展开筛选 Sheet。
 * - 列表行长按弹出底部操作菜单（编辑 / 复制金额 / 删除）替代 swipe-to-delete。
 *
 * **对外签名保持不变**：initialCategoryId / initialType / initialStartDate /
 * initialEndDate / onNavigateToDetail / viewModel —— NavHost 无需改动。
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
    // 绑定到 Composable 生命周期的 scope，用于在 Flow.collect 的 inline lambda 中
    // 启动子协程（collect 的 lambda 不是 CoroutineScope 接收者，launch 必须挂在外部 scope）。
    val snackbarScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // 从统计页带筛选跳入时显示返回箭头（走系统返回栈，不改对外签名）。
    // 四个筛选入参任一非空即视为深链，包括 endDate —— 只带日期范围的情况
    // 将来一定会出现，不能到那时才发现箭头漏了。
    val deepLinked = initialCategoryId != null || initialType != null ||
        initialStartDate != null || initialEndDate != null
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val onBack: (() -> Unit)? = if (deepLinked && backDispatcher != null) {
        { backDispatcher.onBackPressed() }
    } else {
        null
    }

    // 筛选 Sheet 可见性。TopBar 的「筛选」图标与筛选区里的触发按钮共用它。
    var showFilterSheet by remember { mutableStateOf(false) }

    // 长按操作目标（null 表示不显示操作 Sheet）。
    var actionTarget by remember { mutableStateOf<Transaction?>(null) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is TransactionsViewModel.UiEvent.ShowToast ->
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(event.message)
                    }
                is TransactionsViewModel.UiEvent.ShowDeleteUndo ->
                    snackbarScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除",
                            actionLabel = "撤销",
                            withDismissAction = true,
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete()
                        }
                    }
                is TransactionsViewModel.UiEvent.RefreshList ->
                    pagingItems.refresh()
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    val activeCount = (if (uiState.filterCategoryId != null) 1 else 0) +
        uiState.filterTags.size +
        (if (uiState.filterDateLabel != "全部") 1 else 0)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "流水",
                onBack = onBack,
                actions = {
                    FilterAction(
                        activeCount = activeCount,
                        onClick = { showFilterSheet = true },
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TransactionsFilters(
                state = uiState,
                filterSheetVisible = showFilterSheet,
                onFilterSheetVisibleChange = { showFilterSheet = it },
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
                    onSelectThisWeek = viewModel::onSelectThisWeek,
                    onTypeChanged = viewModel::onFilterTypeChanged,
                    onCategoryChanged = viewModel::setCategoryFilter,
                    onTagToggled = viewModel::setTagFilter,
                    onClearAllTags = viewModel::clearAllTags,
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
                    onItemClick = onNavigateToDetail,
                    onLongPress = { actionTarget = it },
                )
            }
        }
    }

    // ============ 长按操作菜单 ============
    val target = actionTarget
    if (target != null) {
        val sheetState = rememberModalBottomSheetState()
        val sheetScope = rememberCoroutineScope()
        val dismiss = {
            sheetScope.launch {
                sheetState.hide()
                actionTarget = null
            }
            Unit
        }
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState,
        ) {
            TransactionActionSheet(
                transaction = target,
                onEdit = {
                    target.id?.let(onNavigateToDetail)
                    dismiss()
                },
                onCopyAmount = {
                    clipboard.setText(
                        AnnotatedString(AmountFormatter.toYuanDisplay(target.amount)),
                    )
                    snackbarScope.launch { snackbarHostState.showSnackbar("已复制金额") }
                    dismiss()
                },
                onDelete = {
                    viewModel.onDeleteTransaction(target)
                    dismiss()
                },
            )
        }
    }
}

/**
 * TopBar 右侧「筛选」action：图标 + 生效数量角标。
 */
@Composable
private fun FilterAction(
    activeCount: Int,
    onClick: () -> Unit,
) {
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = "筛选",
            )
        }
        if (activeCount > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 6.dp),
            ) {
                Text("$activeCount")
            }
        }
    }
}

/**
 * 长按弹出的操作菜单。高频动作：编辑 / 复制金额 / 删除（删除为破坏性，用危险色）。
 */
@Composable
private fun TransactionActionSheet(
    transaction: Transaction,
    onEdit: () -> Unit,
    onCopyAmount: () -> Unit,
    onDelete: () -> Unit,
) {
    val semantics = MaterialTheme.semantic
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Tokens.Spacing.lg),
    ) {
        // 头部：被操作的交易概览
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Tokens.Spacing.screenHorizontal,
                    vertical = Tokens.Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryAvatar(
                icon = transaction.categoryIcon,
                modifier = Modifier.size(Tokens.Avatar.lg),
            )
            Spacer(modifier = Modifier.width(Tokens.Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.categoryName ?: "未分类",
                    style = AppTextStyles.ListTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!transaction.description.isNullOrBlank()) {
                    Text(
                        text = transaction.description,
                        style = AppTextStyles.ListSubtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(Tokens.Spacing.sm))
            AmountText(
                amount = transaction.amount,
                type = transaction.type,
                style = AmountTypography.Row,
                showSign = true,
            )
        }

        ActionRow(
            icon = Icons.Outlined.Edit,
            label = "编辑",
            onClick = onEdit,
        )
        ActionRow(
            icon = Icons.Outlined.ContentCopy,
            label = "复制金额",
            onClick = onCopyAmount,
        )
        ActionRow(
            icon = Icons.Outlined.Delete,
            label = "删除",
            onClick = onDelete,
            tint = semantics.danger,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(Tokens.TouchTarget.xlarge)
            .padding(horizontal = Tokens.Spacing.screenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Tokens.Avatar.md)
                .padding(end = Tokens.Spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
        }
        Spacer(modifier = Modifier.width(Tokens.Spacing.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}
