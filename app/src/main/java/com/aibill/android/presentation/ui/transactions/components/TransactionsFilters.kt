package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens

/**
 * 流水筛选区：日期范围 + 类型 + 分类 + 标签 + 合计
 */
data class TransactionsFiltersCallbacks(
    val onClearDate: () -> Unit = {},
    val onJumpToCurrentMonth: () -> Unit = {},
    val onSelectLastMonth: () -> Unit = {},
    val onSelectCustomDate: (Long, Long) -> Unit = { _, _ -> },
    val onTypeChanged: (String) -> Unit = {},
    val onCategoryChanged: (Int?) -> Unit = {},
    val onTagToggled: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsFilters(
    state: com.aibill.android.presentation.ui.transactions.TransactionsViewModel.TransactionsUiState,
    categories: List<Category>,
    availableTags: List<String>,
    callbacks: TransactionsFiltersCallbacks = TransactionsFiltersCallbacks(),
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        DateFilterRow(
            filterDateLabel = state.filterDateLabel,
            onClearDate = callbacks.onClearDate,
            onJumpToCurrentMonth = callbacks.onJumpToCurrentMonth,
            onSelectLastMonth = callbacks.onSelectLastMonth,
            onShowCustomDatePicker = { showDatePicker = true },
        )

        if (showDatePicker) {
            DateRangePickerDialog(
                onDismiss = { showDatePicker = false },
                onConfirm = { start, end ->
                    callbacks.onSelectCustomDate(start, end)
                    showDatePicker = false
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs + Tokens.Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.filterType == "all",
                onClick = { callbacks.onTypeChanged("all") },
                label = { Text("全部") },
            )
            FilterChip(
                selected = state.filterType == "expense",
                onClick = { callbacks.onTypeChanged("expense") },
                label = { Text("支出") },
            )
            FilterChip(
                selected = state.filterType == "income",
                onClick = { callbacks.onTypeChanged("income") },
                label = { Text("收入") },
            )
            if (categories.isNotEmpty()) {
                CategoryFilterDropdown(
                    categories = categories,
                    selectedCategoryId = state.filterCategoryId,
                    onCategorySelected = callbacks.onCategoryChanged,
                )
            }
        }

        if (availableTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = Tokens.Spacing.xl, end = Tokens.Spacing.md, bottom = Tokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                availableTags.forEach { tag ->
                    FilterChip(
                        selected = tag in state.filterTags,
                        onClick = { callbacks.onTagToggled(tag) },
                        label = { Text("#$tag") },
                    )
                }
            }
        }

        if (state.filterStartDate != null && (state.periodExpense > 0 || state.periodIncome > 0)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                if (state.periodExpense > 0) {
                    Text(
                        text = "支出 ¥${"%.2f".format(state.periodExpense / 100.0)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ExpenseColor,
                    )
                }
                if (state.periodIncome > 0) {
                    Text(
                        text = "收入 ¥${"%.2f".format(state.periodIncome / 100.0)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = IncomeColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateFilterRow(
    filterDateLabel: String,
    onClearDate: () -> Unit,
    onJumpToCurrentMonth: () -> Unit,
    onSelectLastMonth: () -> Unit,
    onShowCustomDatePicker: () -> Unit,
) {
    val isCustomRange = filterDateLabel != "全部" && filterDateLabel != "本月" && filterDateLabel != "上月"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs + Tokens.Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filterDateLabel == "全部",
            onClick = onClearDate,
            label = { Text("全部") },
        )
        FilterChip(
            selected = filterDateLabel == "本月",
            onClick = onJumpToCurrentMonth,
            label = { Text("本月") },
        )
        FilterChip(
            selected = filterDateLabel == "上月",
            onClick = onSelectLastMonth,
            label = { Text("上月") },
        )
        FilterChip(
            selected = isCustomRange,
            onClick = onShowCustomDatePicker,
            label = { Text(if (isCustomRange) filterDateLabel else "自定义") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
) {
    val dateRangePickerState = rememberDateRangePickerState()
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val start = dateRangePickerState.selectedStartDateMillis
                    val end = dateRangePickerState.selectedEndDateMillis
                    if (start != null && end != null) onConfirm(start, end)
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        androidx.compose.material3.DateRangePicker(
            state = dateRangePickerState,
            modifier = Modifier.height(500.dp),
        )
    }
}

@Composable
private fun CategoryFilterDropdown(
    categories: List<Category>,
    selectedCategoryId: Int?,
    onCategorySelected: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories
        .firstOrNull { it.id == selectedCategoryId }
        ?.let { "${it.icon} ${it.name}" } ?: "分类"

    androidx.compose.foundation.layout.Box {
        FilterChip(
            selected = selectedCategoryId != null,
            onClick = { expanded = true },
            label = { Text(selectedName) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.height(300.dp),
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
