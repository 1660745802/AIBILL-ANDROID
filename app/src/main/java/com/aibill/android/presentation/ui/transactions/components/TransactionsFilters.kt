package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.components.AppChip
import com.aibill.android.presentation.components.ChipRow
import com.aibill.android.presentation.components.FilterTriggerChip
import com.aibill.android.presentation.components.FlowChips
import com.aibill.android.presentation.components.RemovableFilterChip
import com.aibill.android.presentation.theme.PrimaryButtonBlock
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import kotlinx.coroutines.launch

/**
 * 流水筛选区。**重设计为「一行常驻 + 按需展开」**：
 * - 常驻行：`全部 / 支出 / 收入`（共享 [AppChip]，支出/收入选中态用语义色）
 *   + 右侧 [FilterTriggerChip]（显示生效的筛选数量）。
 * - 摘要行（仅当有分类/标签筛选时出现）：[RemovableFilterChip] 横排 + 「清空」。
 * - 时段 / 分类 / 标签全部折进 BottomSheet；时段是 Sheet 内第一个分区。
 *
 * 高度从旧版 3 行（~140dp）压到 1~2 行，把可视区还给列表。
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
    val semantics = MaterialTheme.semantic
    var showFilterSheet by remember { mutableStateOf(false) }

    // 生效筛选数量：分类(1) + 每个标签(1) + 时段非「全部」(1)
    val dateActive = state.filterDateLabel != "全部"
    val activeCount = (if (state.filterCategoryId != null) 1 else 0) +
        state.filterTags.size +
        (if (dateActive) 1 else 0)

    val selectedCategoryName = state.filterCategoryId
        ?.let { id -> categories.firstOrNull { it.id == id }?.name }
    val hasSummary = selectedCategoryName != null || state.filterTags.isNotEmpty() || dateActive

    Column(modifier = modifier.fillMaxWidth()) {
        // ============ 常驻行：类型 + 筛选触发 ============
        ChipRow {
            AppChip(
                selected = state.filterType == "all",
                onClick = { callbacks.onTypeChanged("all") },
                label = "全部",
            )
            AppChip(
                selected = state.filterType == "expense",
                onClick = { callbacks.onTypeChanged("expense") },
                label = "支出",
                accent = semantics.expense,
            )
            AppChip(
                selected = state.filterType == "income",
                onClick = { callbacks.onTypeChanged("income") },
                label = "收入",
                accent = semantics.income,
            )
            Spacer(modifier = Modifier.width(Tokens.Spacing.xxs))
            FilterTriggerChip(
                label = "筛选",
                activeCount = activeCount,
                onClick = { showFilterSheet = true },
                icon = Icons.Outlined.Tune,
            )
        }

        // ============ 摘要行：已生效筛选（可点即删） ============
        if (hasSummary) {
            ChipRow(verticalPadding = Tokens.Spacing.xxs) {
                if (dateActive) {
                    RemovableFilterChip(
                        label = state.filterDateLabel,
                        onRemove = callbacks.onClearDate,
                    )
                }
                if (selectedCategoryName != null) {
                    RemovableFilterChip(
                        label = selectedCategoryName,
                        onRemove = { callbacks.onCategoryChanged(null) },
                    )
                }
                state.filterTags.forEach { tag ->
                    RemovableFilterChip(
                        label = "#$tag",
                        onRemove = { callbacks.onTagToggled(tag) },
                    )
                }
                Spacer(modifier = Modifier.width(Tokens.Spacing.xxs))
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .height(Tokens.Chip.height)
                        .padding(horizontal = Tokens.Spacing.xs)
                        .clickableText {
                            callbacks.onClearDate()
                            callbacks.onCategoryChanged(null)
                            callbacks.onClearAllTags()
                        },
                )
            }
        }

        // ============ 期间合计（有日期筛选且有数据时）============
        if (state.filterStartDate != null && (state.periodExpense > 0 || state.periodIncome > 0)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Tokens.Spacing.screenHorizontal,
                        vertical = Tokens.Spacing.xs,
                    ),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                if (state.periodExpense > 0) {
                    com.aibill.android.presentation.components.AmountText(
                        amount = state.periodExpense,
                        type = com.aibill.android.domain.model.TransactionType.EXPENSE,
                        style = com.aibill.android.presentation.theme.AmountTypography.Chip,
                        showSign = false,
                        color = semantics.expense,
                    )
                }
                if (state.periodIncome > 0) {
                    com.aibill.android.presentation.components.AmountText(
                        amount = state.periodIncome,
                        type = com.aibill.android.domain.model.TransactionType.INCOME,
                        style = com.aibill.android.presentation.theme.AmountTypography.Chip,
                        showSign = false,
                        color = semantics.income,
                    )
                }
            }
        }

        // ============ 筛选 Sheet ============
        // 条件渲染 + 关闭时先播完 hide 动画再销毁。
        if (showFilterSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val sheetScope = rememberCoroutineScope()
            val closeSheet = {
                sheetScope.launch {
                    sheetState.hide()
                    showFilterSheet = false
                }
                Unit
            }
            ModalBottomSheet(
                onDismissRequest = { closeSheet() },
                sheetState = sheetState,
            ) {
                FilterSheetContent(
                    state = state,
                    categories = categories,
                    availableTags = availableTags,
                    callbacks = callbacks,
                    onClose = closeSheet,
                )
            }
        }
    }
}

/**
 * 不占 48dp 触控区的轻量文字点击（用于「清空」这种次要操作）。
 */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

// =============================================================================
// 筛选 Sheet 内容：时段（第一分区）→ 分类 → 标签 → 完成按钮
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheetContent(
    state: com.aibill.android.presentation.ui.transactions.TransactionsViewModel.TransactionsUiState,
    categories: List<Category>,
    availableTags: List<String>,
    callbacks: TransactionsFiltersCallbacks,
    onClose: () -> Unit,
) {
    val semantics = MaterialTheme.semantic
    var showDatePicker by remember { mutableStateOf(false) }
    val hasAnyFilter = state.filterCategoryId != null ||
        state.filterTags.isNotEmpty() ||
        state.filterDateLabel != "全部"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Tokens.Spacing.screenHorizontal,
                end = Tokens.Spacing.screenHorizontal,
                bottom = Tokens.Spacing.lg,
            ),
    ) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "筛选",
                style = MaterialTheme.typography.titleMedium,
            )
            if (hasAnyFilter) {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickableText {
                        callbacks.onClearDate()
                        callbacks.onCategoryChanged(null)
                        callbacks.onClearAllTags()
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.lg))

        // ---- 分区 1：时段 ----
        SheetSectionLabel("时段")
        FlowChips {
            val label = state.filterDateLabel
            AppChip(
                selected = label == "本周",
                onClick = callbacks.onSelectThisWeek,
                label = "本周",
            )
            AppChip(
                selected = label == "本月",
                onClick = callbacks.onJumpToCurrentMonth,
                label = "本月",
            )
            AppChip(
                selected = label == "上月",
                onClick = callbacks.onSelectLastMonth,
                label = "上月",
            )
            AppChip(
                selected = label == "全部",
                onClick = callbacks.onClearDate,
                label = "全部",
            )
            val isCustom = label != "全部" && label != "本月" &&
                label != "上月" && label != "本周"
            AppChip(
                selected = isCustom,
                onClick = { showDatePicker = true },
                label = if (isCustom) label else "自定义",
            )
        }

        // ---- 分区 2：分类 ----
        if (categories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Tokens.Spacing.lg))
            SheetSectionLabel("分类")
            FlowChips {
                AppChip(
                    selected = state.filterCategoryId == null,
                    onClick = { callbacks.onCategoryChanged(null) },
                    label = "全部",
                )
                categories.forEach { cat ->
                    AppChip(
                        selected = state.filterCategoryId == cat.id,
                        onClick = { callbacks.onCategoryChanged(cat.id) },
                        label = cat.name,
                    )
                }
            }
        }

        // ---- 分区 3：标签 ----
        if (availableTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Tokens.Spacing.lg))
            SheetSectionLabel("标签")
            FlowChips {
                availableTags.forEach { tag ->
                    AppChip(
                        selected = tag in state.filterTags,
                        onClick = { callbacks.onTagToggled(tag) },
                        label = "#$tag",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.xl))

        PrimaryButtonBlock(
            text = "完成",
            onClick = onClose,
        )
    }

    if (showDatePicker) {
        DateRangePickerDialog(
            onDismiss = { showDatePicker = false },
            onConfirm = { start, end ->
                callbacks.onSelectCustomDate(start, end)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        style = com.aibill.android.presentation.theme.AppTextStyles.SectionLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = Tokens.Spacing.sm),
    )
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
