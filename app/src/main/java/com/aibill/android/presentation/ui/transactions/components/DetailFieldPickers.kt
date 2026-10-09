package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.components.AppChip
import com.aibill.android.presentation.components.FlowChips

/**
 * 详情页类型切换。
 *
 * **已迁移到 Hero 区的 [com.aibill.android.presentation.components.TypeSegmentedControl]**。
 * 本函数保留一个基于共享 [AppChip] 的轻量实现，供需要平铺三选的内嵌场景使用，
 * 不再手写 M3 FilterChip（高度/圆角与其它 chip 对不齐）。
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
    FlowChips(modifier = modifier) {
        types.forEach { (value, label) ->
            AppChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = label,
            )
        }
    }
}

/**
 * 详情页分类选择（共享 [AppChip] 的 FlowChips 平铺）。
 */
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
            modifier = modifier,
        )
        return
    }
    FlowChips(modifier = modifier) {
        availableCategories.forEach { cat ->
            AppChip(
                selected = selectedCategoryId == cat.id,
                onClick = { onSelect(cat.id) },
                label = "${cat.icon} ${cat.name}",
            )
        }
    }
}

/**
 * 详情页账户选择（共享 [AppChip] 的 FlowChips 平铺）。含「无」选项。
 */
@Composable
fun DetailAccountPickerRow(
    availableAccounts: List<Account>,
    selectedAccountId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowChips(modifier = modifier) {
        AppChip(
            selected = selectedAccountId == null,
            onClick = { onSelect(null) },
            label = "无",
        )
        availableAccounts.forEach { acc ->
            AppChip(
                selected = selectedAccountId == acc.id,
                onClick = { onSelect(acc.id) },
                label = "${acc.icon} ${acc.name}",
            )
        }
    }
}
