package com.aibill.android.presentation.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.TransactionType

/**
 * 通用交易编辑对话框。
 * 首页 AI 确认和通知中心确认统一复用此弹窗。
 * 支持：金额/类型(支出/收入/转账)/分类选择/备注/转账账户选择。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionEditDialog(
    initialAmount: Int, // 分
    initialType: String, // expense/income/transfer
    initialCategoryId: Int? = null,
    initialDescription: String? = null,
    initialAccountId: Int? = null,
    initialTargetAccountId: Int? = null,
    categoriesByType: Map<String, List<Category>>,
    accounts: List<Account> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, type: String, categoryId: Int?, description: String, accountId: Int?, targetAccountId: Int?) -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var amountText by remember { mutableStateOf(if (initialAmount > 0) "%.2f".format(initialAmount / 100.0) else "") }
    var description by remember { mutableStateOf(initialDescription ?: "") }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryId) }
    var selectedAccountId by remember { mutableStateOf(initialAccountId) }
    var selectedTargetAccountId by remember { mutableStateOf(initialTargetAccountId) }

    val typeKey = if (type == "income") "income" else "expense"
    val availableCategories = categoriesByType[typeKey].orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并确认") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 类型三选
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == "expense",
                        onClick = { type = "expense" },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = type == "income",
                        onClick = { type = "income" },
                        label = { Text("收入") }
                    )
                    FilterChip(
                        selected = type == "transfer",
                        onClick = { type = "transfer" },
                        label = { Text("转账") }
                    )
                }

                // 金额
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                            amountText = newVal
                        }
                    },
                    label = { Text("金额 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                // 备注
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (type != "transfer") {
                    // 分类选择
                    Text("分类", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (availableCategories.isEmpty()) {
                        Text("暂无可选分类", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            availableCategories.forEach { cat ->
                                FilterChip(
                                    selected = selectedCategoryId == cat.id,
                                    onClick = { selectedCategoryId = cat.id },
                                    label = { Text("${cat.icon} ${cat.name}") },
                                )
                            }
                        }
                    }
                } else {
                    // 转账：账户选择
                    Text("转账不计入收支统计", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SimpleAccountPicker(
                        label = "从",
                        accounts = accounts,
                        selectedId = selectedAccountId,
                        onSelect = { selectedAccountId = it },
                    )
                    SimpleAccountPicker(
                        label = "到",
                        accounts = accounts.filter { it.id != selectedAccountId },
                        selectedId = selectedTargetAccountId,
                        onSelect = { selectedTargetAccountId = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = Math.round((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    val finalCategoryId = if (type == "transfer") null else selectedCategoryId
                    onConfirm(
                        cents, type, finalCategoryId, description,
                        if (type == "transfer") selectedAccountId else null,
                        if (type == "transfer") selectedTargetAccountId else null,
                    )
                },
                enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0
            ) { Text("确认记账") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SimpleAccountPicker(
    label: String,
    accounts: List<Account>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    val selectedName = accounts.firstOrNull { it.id == selectedId }?.let { "${it.icon} ${it.name}" } ?: "选择账户"
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            singleLine = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("${account.icon} ${account.name}") },
                    onClick = { onSelect(account.id); expanded = false },
                )
            }
        }
    }
}
