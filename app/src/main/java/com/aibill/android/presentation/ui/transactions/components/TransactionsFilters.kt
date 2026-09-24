package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 流水筛选区。**重构为 3 行紧凑布局**：
 * - 行 1：时段（今日/本周/本月/上月/全部 + 自定义）
 * - 行 2：类型（全部/支出/收入）
 * - 行 3：折叠筛选（点击底部 sheet 选择分类/标签/账户）
 *
 * 设计原则：
 * - 不显示冗余信息（筛选标签太长用"更多 ▾"代替）
 * - 标签/账户从 chip 行移到 sheet
 */
data class TransactionsFiltersCallbacks(
    val onClearDate: () -> Unit = {},
    val onJumpToCurrentMonth: () -> Unit = {},
    val onSelectLastMonth: () -> Unit = {},
    val onSelectCustomDate: (Long, Long) -> Unit = { _, _ -> },
    val onSelectThisWeek: () -> Unit = {},
    val onTypeChanged: (String) -> Unit = {},
    val onCategoryChanged: (Int?) -> Unit = {},
    val onTagToggled: (String) -> Unit = {},
    val onClearAllTags: () -> Unit = {},
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
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ============ 行 1：时段 ============
        DateFilterRow(
            filterDateLabel = state.filterDateLabel,
            onClearDate = callbacks.onClearDate,
            onJumpToCurrentMonth = callbacks.onJumpToCurrentMonth,
            onSelectLastMonth = callbacks.onSelectLastMonth,
            onSelectThisWeek = callbacks.onSelectThisWeek,
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

        // ============ 行 2：类型 + 筛选触发按钮（不含已选筛选）============
        TypeAndFilterRow(
            state = state,
            onTypeChanged = callbacks.onTypeChanged,
            onFilterClick = { showFilterSheet = true },
        )

        // ============ 行 3：已选筛选摘要（仅在有筛选时显示）============
        SelectedFiltersRow(
            state = state,
            categories = categories,
            onClearCategory = { callbacks.onCategoryChanged(null) },
            onClearTag = { callbacks.onTagToggled(it) },
        )

        // ============ 筛选 Sheet ============
        // **关键**：上一版 always render ModalBottomSheet 会导致 Hidden 状态下
        // 仍占据 fillMaxSize 拦截整个屏幕点击事件，流水页“什么都点不了”。
        // 改回 if (showFilterSheet) 条件渲染 + LaunchedEffect 跟踪 sheetState.isVisible
        // 控制 showFilterSheet → 动画完成后才 dispose，避免双重动画闪退。
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetScope = rememberCoroutineScope()

        // 响应外部状态变化：当 showFilterSheet=true 时调用 sheetState.show()
        LaunchedEffect(showFilterSheet) {
            if (showFilterSheet) sheetState.show()
        }

        // 跟踪 sheetState 隐藏：动画完成后才设 showFilterSheet=false
        // 这样 if 条件变为 false，ModalBottomSheet 才被 dispose（避免双重动画）
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.isVisible }.collect { visible ->
                if (!visible && showFilterSheet) {
                    showFilterSheet = false
                }
            }
        }

        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    sheetScope.launch { sheetState.hide() }
                },
                sheetState = sheetState,
            ) {
                FilterSheetContent(
                    state = state,
                    categories = categories,
                    availableTags = availableTags,
                    onCategorySelected = { callbacks.onCategoryChanged(it) },
                    onTagToggled = callbacks.onTagToggled,
                    onClearAll = {
                        callbacks.onCategoryChanged(null)
                        callbacks.onClearAllTags()
                    },
                    onClose = {
                        sheetScope.launch { sheetState.hide() }
                    },
                )
            }
        }

        // ============ 当前筛选下的合计（按需）============
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

// =============================================================================
// 行 1：时段筛选 chip 行
// =============================================================================

@Composable
private fun DateFilterRow(
    filterDateLabel: String,
    onClearDate: () -> Unit,
    onJumpToCurrentMonth: () -> Unit,
    onSelectLastMonth: () -> Unit,
    onSelectThisWeek: () -> Unit,
    onShowCustomDatePicker: () -> Unit,
) {
    val isCustomRange = filterDateLabel != "全部" &&
            filterDateLabel != "本月" &&
            filterDateLabel != "上月" &&
            filterDateLabel != "本周"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filterDateLabel == "本周",
            onClick = onSelectThisWeek,
            label = { Text("本周") },
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
            selected = filterDateLabel == "全部",
            onClick = onClearDate,
            label = { Text("全部") },
        )
        // 自定义范围：紧凑"更多"按钮代替长标签 chip
        MoreDateButton(
            label = if (isCustomRange) filterDateLabel else "更多",
            isSelected = isCustomRange,
            onClick = onShowCustomDatePicker,
        )
    }
}

/**
 * 自定义日期按钮：紧凑，标签动态变化。
 */
@Composable
private fun MoreDateButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =============================================================================
// 行 3：折叠筛选按钮 + 当前筛选摘要
// =============================================================================

@Composable
private fun TypeAndFilterRow(
    state: com.aibill.android.presentation.ui.transactions.TransactionsViewModel.TransactionsUiState,
    onTypeChanged: (String) -> Unit,
    onFilterClick: () -> Unit,
) {
    val hasFilter = state.filterCategoryId != null || state.filterTags.isNotEmpty()
    val filterCount = (if (state.filterCategoryId != null) 1 else 0) + state.filterTags.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.filterType == "all",
            onClick = { onTypeChanged("all") },
            label = { Text("全部") },
        )
        FilterChip(
            selected = state.filterType == "expense",
            onClick = { onTypeChanged("expense") },
            label = { Text("支出") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ExpenseColor.copy(alpha = 0.12f),
                selectedLabelColor = ExpenseColor,
            ),
        )
        FilterChip(
            selected = state.filterType == "income",
            onClick = { onTypeChanged("income") },
            label = { Text("收入") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = IncomeColor.copy(alpha = 0.12f),
                selectedLabelColor = IncomeColor,
            ),
        )
        FilterTriggerButton(
            label = "筛选",
            count = filterCount,
            isActive = hasFilter,
            onClick = onFilterClick,
        )
    }
}

/**
 * 行 3：已选筛选摘要（仅在有筛选时显示，独立行避免挤占类型筛选）。
 */
@Composable
private fun SelectedFiltersRow(
    state: com.aibill.android.presentation.ui.transactions.TransactionsViewModel.TransactionsUiState,
    categories: List<Category>,
    onClearCategory: () -> Unit,
    onClearTag: (String) -> Unit,
) {
    val categoryName = state.filterCategoryId
        ?.let { id -> categories.firstOrNull { it.id == id }?.let { "${it.icon} ${it.name}" } }
    val hasFilter = categoryName != null || state.filterTags.isNotEmpty()
    if (!hasFilter) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (categoryName != null) {
            RemovableChip(
                text = categoryName,
                onRemove = onClearCategory,
            )
        }
        state.filterTags.forEach { tag ->
            RemovableChip(
                text = "#$tag",
                onRemove = { onClearTag(tag) },
            )
        }
    }
}

@Composable
private fun FilterTriggerButton(
    label: String,
    count: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Tune,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            Text(
                text = "·$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "×",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =============================================================================
// 筛选 Sheet
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheetContent(
    state: com.aibill.android.presentation.ui.transactions.TransactionsViewModel.TransactionsUiState,
    categories: List<Category>,
    availableTags: List<String>,
    onCategorySelected: (Int?) -> Unit,
    onTagToggled: (String) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.padding(Tokens.Spacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "筛选",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.filterCategoryId != null || state.filterTags.isNotEmpty()) {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        onClearAll()
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.md))

        if (categories.isNotEmpty()) {
            Text(
                text = "分类",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Tokens.Spacing.xs))
            // 全部分类 + 各分类（网格式 chip）
            FlowChipGroup(
                items = listOf<Pair<Int?, String>>(null to "全部") +
                        categories.map { it.id to "${it.icon} ${it.name}" },
                selectedId = state.filterCategoryId,
                onSelect = onCategorySelected,
            )
        }

        if (availableTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Tokens.Spacing.md))
            Text(
                text = "标签",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Tokens.Spacing.xs))
            FlowChipGroup(
                items = availableTags.map { it to "#$it" },
                selectedIds = state.filterTags.toSet(),
                onToggle = { onTagToggled(it) },
            )
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.lg))

        androidx.compose.material3.Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("完成")
        }
    }
}

/** 单选 chip 组（用于分类） */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChipGroup(
    items: List<Pair<Int?, String>>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        items.forEach { (id, label) ->
            FilterChip(
                selected = id == selectedId,
                onClick = { onSelect(id) },
                label = { Text(label) },
            )
        }
    }
}

/** 多选 chip 组（用于标签） */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowChipGroup(
    items: List<Pair<String, String>>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        items.forEach { (id, label) ->
            FilterChip(
                selected = id in selectedIds,
                onClick = { onToggle(id) },
                label = { Text(label) },
            )
        }
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
