package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.theme.Tokens

/**
 * 详情页类型切换：支出/收入/转账（FilterChip 风格，可多选中的视觉差异）。
 */
@Composable
fun DetailTypeChipRow(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val types = listOf(
        "expense" to "支出",
        "income" to "收入",
        "transfer" to "转账",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        types.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * 详情页分类选择（FilterChip FlowRow）。
 * 与新增/编辑场景的 [AccountPicker] 下拉模式不同：
 * 详情场景分类数少且需要平铺展示选中状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailCategoryPickerRow(
    availableCategories: List<Category>,
    selectedCategoryId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availableCategories.isEmpty()) {
        Text(
            text = "暂无分类",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        availableCategories.forEach { cat ->
            FilterChip(
                selected = selectedCategoryId == cat.id,
                onClick = { onSelect(cat.id) },
                label = { Text("${cat.icon} ${cat.name}") },
            )
        }
    }
}

/**
 * 详情页账户选择（FilterChip FlowRow）。含"无"选项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailAccountPickerRow(
    availableAccounts: List<Account>,
    selectedAccountId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        FilterChip(
            selected = selectedAccountId == null,
            onClick = { onSelect(null) },
            label = { Text("无") },
        )
        availableAccounts.forEach { acc ->
            FilterChip(
                selected = selectedAccountId == acc.id,
                onClick = { onSelect(acc.id) },
                label = { Text("${acc.icon} ${acc.name}") },
            )
        }
    }
}
