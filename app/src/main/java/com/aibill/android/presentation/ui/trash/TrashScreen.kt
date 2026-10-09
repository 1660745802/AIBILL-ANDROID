package com.aibill.android.presentation.ui.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.ErrorState
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.GroupedRow
import com.aibill.android.presentation.components.TrailingIconButton
import com.aibill.android.presentation.components.LoadingState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "回收站", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> {
                    LoadingState()
                }
                uiState.error != null -> {
                    ErrorState(
                        title = "没能加载回收站",
                        subtitle = uiState.error ?: "请检查网络后重试",
                        icon = Icons.Default.DeleteOutline,
                        onAction = { viewModel.loadTrash() },
                    )
                }
                uiState.items.isEmpty() -> {
                    EmptyState(
                        title = "回收站是空的",
                        subtitle = "删除的记录会先放到这里，30 天内都可以恢复",
                        icon = Icons.Default.DeleteOutline,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Tokens.Spacing.screenHorizontal,
                            end = Tokens.Spacing.screenHorizontal,
                            top = Tokens.Spacing.md,
                            bottom = Tokens.Spacing.huge,
                        ),
                    ) {
                        item(key = "group") {
                            GroupedList {
                                uiState.items.forEach { item ->
                                    TrashRow(
                                        transaction = item,
                                        onRestore = { item.id?.let { viewModel.restoreTransaction(it) } },
                                        onPermanentDelete = { deleteConfirmId = item.id },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 永久删除确认弹窗
    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("永久删除") },
            text = { Text("确定永久删除这笔记录吗？此操作不可恢复。") },
            confirmButton = {
                AppTextButton(
                    text = "删除",
                    onClick = {
                        viewModel.permanentDelete(id)
                        deleteConfirmId = null
                    },
                    isDestructive = true,
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deleteConfirmId = null })
            }
        )
    }
}

@Composable
private fun TrashRow(
    // PR #61：TrashViewModel 改用 Domain Transaction
    transaction: com.aibill.android.domain.model.Transaction,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    val isExpense = transaction.type == com.aibill.android.domain.model.TransactionType.EXPENSE
    val prefix = if (isExpense) "-" else "+"
    val amountColor = if (isExpense) MaterialTheme.semantic.expense else MaterialTheme.semantic.income
    var menuExpanded by remember { mutableStateOf(false) }

    GroupedRow(
        title = transaction.description
            ?: transaction.categoryName
            ?: "未分类",
        subtitle = transaction.date,
        showChevron = false,
        // 回收站里也要能一眼看出「这是哪类消费」，用分类的 emoji 头像
        leadingEmoji = transaction.categoryIcon ?: DEFAULT_TX_ICON,
        trailing = {
            Text(
                text = "$prefix${AmountFormatter.toYuanDisplay(transaction.amount)}",
                style = AmountTypography.Stat,
                color = amountColor,
            )
            // 统一走溢出菜单（与分类管理/账户管理一致）。
            // 顺带把「永久删除」收进菜单里，不再裸露在行尾 —— 破坏性操作不该
            // 和「恢复」这种可逆操作并排放在最容易被手指误触的位置。
            Box {
                TrailingIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                        },
                        text = { Text("恢复") },
                        onClick = {
                            menuExpanded = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.semantic.danger,
                            )
                        },
                        text = { Text("永久删除", color = MaterialTheme.semantic.danger) },
                        onClick = {
                            menuExpanded = false
                            onPermanentDelete()
                        },
                    )
                }
            }
        },
    )
}

/** 分类缺失时的占位图标，与 TransactionRow / CategoryAvatar 保持一致。 */
private const val DEFAULT_TX_ICON = "📝"
