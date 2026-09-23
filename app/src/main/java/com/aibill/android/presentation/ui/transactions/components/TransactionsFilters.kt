package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.time.YearMonth

/**
 * 流水筛选区：日期范围 + 类型 + 分类 + 标签 + 合计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsFilters(
    filterDateLabel: String,
    filterType: String,
    filterCategoryId: Int?,
    filterTags: List<String>,
    filterStartDate: String?,
    periodExpense: Int,
    periodIncome: Int,
    categories: List<Category>,
    availableTags: List<String>,
    onClearDate: () -> Unit,
    onJumpToCurrentMonth: () -> Unit,
    onSelectLastMonth: () -> Unit,
    onSelectCustomDate: (Long, Long) -> Unit,
    onTypeChanged: (String) -> Unit,
    onCategoryChanged: (Int?) -> Unit,
    onTagToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        DateFilterRow(
            filterDateLabel = filterDateLabel,
            onClearDate = onClearDate,
            onJumpToCurrentMonth = onJumpToCurrentMonth,
            onSelectLastMonth = onSelectLastMonth,
            onShowCustomDatePicker = { showDatePicker = true },
        )

        if (showDatePicker) {
            DateRangePickerDialog(
                onDismiss = { showDatePicker = false },
                onConfirm = { start, end ->
                    onSelectCustomDate(start, end)
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
                selected = filterType == "all",
                onClick = { onTypeChanged("all") },
                label = { Text("全部") },
            )
            FilterChip(
                selected = filterType == "expense",
                onClick = { onTypeChanged("expense") },
                label = { Text("支出") },
            )
            FilterChip(
                selected = filterType == "income",
                onClick = { onTypeChanged("income") },
                label = { Text("收入") },
            )
            if (categories.isNotEmpty()) {
                CategoryFilterDropdown(
                    categories = categories,
                    selectedCategoryId = filterCategoryId,
                    onCategorySelected = onCategoryChanged,
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
                        selected = tag in filterTags,
                        onClick = { onTagToggled(tag) },
                        label = { Text("#$tag") },
                    )
                }
            }
        }

        if (filterStartDate != null && (periodExpense > 0 || periodIncome > 0)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                if (periodExpense > 0) {
                    Text(
                        text = "支出 ¥${"%.2f".format(periodExpense / 100.0)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ExpenseColor,
                    )
                }
                if (periodIncome > 0) {
                    Text(
                        text = "收入 ¥${"%.2f".format(periodIncome / 100.0)}",
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
            onClick = {
                val ym = YearMonth.now().minusMonths(1)
                onSelectLastMonth()
            },
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
