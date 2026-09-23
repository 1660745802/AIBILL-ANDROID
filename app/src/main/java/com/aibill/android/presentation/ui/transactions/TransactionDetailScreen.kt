package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.ui.transactions.components.DetailAccountPickerRow
import com.aibill.android.presentation.ui.transactions.components.DetailCard
import com.aibill.android.presentation.ui.transactions.components.DetailCategoryPickerRow
import com.aibill.android.presentation.ui.transactions.components.DetailTagSection
import com.aibill.android.presentation.ui.transactions.components.DetailTextField
import com.aibill.android.presentation.ui.transactions.components.DetailTypeChipRow
import kotlinx.coroutines.flow.collectLatest

/**
 * 交易详情页。**已重构**：6 个私有 Composable 全部抽到 components/ 目录。
 *
 * - DetailCard / DetailTextField: 详情页统一容器 + 输入框
 * - DetailTypeChipRow / DetailCategoryPickerRow / DetailAccountPickerRow: 字段选择
 * - DetailTagSection: 标签编辑（VM 仍用逗号分隔字符串）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is TransactionDetailViewModel.UiEvent.ShowToast ->
                    snackbarHostState.showSnackbar(event.message)
                is TransactionDetailViewModel.UiEvent.NavigateBack ->
                    onNavigateBack()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "📝 交易详情",
                onBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = viewModel::onDelete,
                        enabled = !uiState.isSaving,
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = ExpenseColor,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(Tokens.Spacing.md))
                Text("加载中...", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Tokens.Spacing.lg)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                ) {
                    Spacer(modifier = Modifier.height(Tokens.Spacing.xs))
                    DetailCard(label = "💰 类型") {
                        DetailTypeChipRow(
                            selected = uiState.type,
                            onSelected = viewModel::onTypeChanged,
                        )
                    }
                    DetailCard(label = "💵 金额") {
                        DetailTextField(
                            value = uiState.amount,
                            onValueChange = viewModel::onAmountChanged,
                            placeholder = "0.00",
                        )
                    }
                    DetailCard(label = "📂 分类") {
                        DetailCategoryPickerRow(
                            availableCategories = uiState.categories,
                            selectedCategoryId = uiState.categoryId,
                            onSelect = viewModel::onCategorySelected,
                        )
                    }
                    DetailCard(label = "🏦 账户") {
                        DetailAccountPickerRow(
                            availableAccounts = uiState.accounts,
                            selectedAccountId = uiState.accountId,
                            onSelect = viewModel::onAccountSelected,
                        )
                    }
                    DetailCard(label = "📝 描述") {
                        DetailTextField(
                            value = uiState.description,
                            onValueChange = viewModel::onDescriptionChanged,
                            placeholder = "添加描述...",
                        )
                    }
                    DetailCard(label = "📅 日期") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Tokens.Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = uiState.date.ifBlank { "未设置" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showDatePicker = true }) {
                                Icon(
                                    Icons.Default.CalendarMonth,
                                    contentDescription = "选日期",
                                    modifier = Modifier.size(Tokens.IconSize.sm),
                                )
                                Spacer(modifier = Modifier.width(Tokens.Spacing.xs))
                                Text("选择")
                            }
                        }
                    }
                    DetailCard(label = "🕐 时间") {
                        DetailTextField(
                            value = uiState.time,
                            onValueChange = viewModel::onTimeChanged,
                            placeholder = "HH:mm",
                        )
                    }
                    DetailCard(label = "🏷️ 标签") {
                        DetailTagSection(
                            tagsText = uiState.tags,
                            onTagsChanged = viewModel::onTagsChanged,
                            availableTags = uiState.availableTags,
                        )
                    }
                    Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                }

                PrimaryButton(
                    text = "保存修改",
                    onClick = viewModel::onSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
                    enabled = !uiState.isSaving,
                    loading = uiState.isSaving,
                )
            }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.date.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        java.time.LocalDate.parse(it).toEpochDay() * 86_400_000L
                    }.getOrNull()
                }
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                        viewModel.onDateChanged(date)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}
