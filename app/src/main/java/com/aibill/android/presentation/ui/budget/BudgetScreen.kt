package com.aibill.android.presentation.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    modifier: Modifier = Modifier,
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetDto?>(null) }
    var deletingBudget by remember { mutableStateOf<BudgetDto?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 订阅 UI 事件 → Snackbar（PRD §5.3 + CONTRIBUTING §11.5 反馈底线）
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is BudgetViewModel.UiEvent.ShowToast -> snackbarHostState.showSnackbar(event.message)
                is BudgetViewModel.UiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("预算管理") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加预算")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("😥", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.error ?: "加载失败",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                SecondaryButton(text = "重试", onClick = { viewModel.refresh() })
            }
        } else if (state.budgets.isEmpty() && state.totalAmount == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("💰", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "还没有设置预算",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "点击右上角 + 添加一个预算吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(16.dp))
                SecondaryButton(text = "添加预算", onClick = { showAddDialog = true })
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                item {
                    TotalBudgetCard(
                        totalAmount = state.totalAmount,
                        totalSpent = state.totalSpent,
                        progress = state.totalProgress,
                    )
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                items(state.budgets, key = { it.id }) { budget ->
                    BudgetItemCard(
                        budget = budget,
                        onEdit = { editingBudget = budget },
                        onDelete = { deletingBudget = budget },
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddBudgetDialog(
            categories = state.categories,
            isAdding = state.isAdding,
            onDismiss = { showAddDialog = false },
            onConfirm = { categoryId, amount ->
                viewModel.onAddBudget(categoryId, amount)
                showAddDialog = false
            }
        )
    }

    editingBudget?.let { target ->
        EditBudgetDialog(
            budget = target,
            isSaving = state.isAdding,
            onDismiss = { editingBudget = null },
            onConfirm = { newAmount ->
                viewModel.onUpdateBudget(target.id, newAmount)
                editingBudget = null
            },
        )
    }

    deletingBudget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingBudget = null },
            title = { Text("删除预算") },
            text = { Text("确定删除「${target.categoryName ?: "总预算"}」的预算？该操作不可撤销。") },
            confirmButton = {
                AppTextButton(
                    text = "删除",
                    onClick = {
                        viewModel.onDeleteBudget(target.id)
                        deletingBudget = null
                    },
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deletingBudget = null })
            },
        )
    }
}

/**
 * 编辑预算金额弹窗（仅金额可改，分类在 AddBudgetDialog 中已选）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBudgetDialog(
    budget: BudgetDto,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (newAmountCents: Int) -> Unit,
) {
    var amountText by rememberSaveable(budget.id) {
        mutableStateOf("%.2f".format(budget.amount / 100.0))
    }
    val cents = amountText.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
    val canConfirm = cents > 0 && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑预算 · ${budget.categoryName ?: "总预算"}") },
        text = {
            OutlinedTextField(
                value = amountText,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() || it == '.' }
                    if (filtered.count { it == '.' } <= 1) {
                        val parts = filtered.split(".")
                        if (parts.size <= 1 || parts[1].length <= 2) {
                            amountText = filtered
                        }
                    }
                },
                label = { Text("预算金额（元）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            AppTextButton(text = "保存", onClick = { if (canConfirm) onConfirm(cents) }, enabled = canConfirm)
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        }
    )
}
