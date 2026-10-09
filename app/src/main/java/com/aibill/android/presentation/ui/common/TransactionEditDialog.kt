package com.aibill.android.presentation.ui.common

import com.aibill.android.presentation.components.AppChip
import com.aibill.android.presentation.components.TypeSegmentedControl
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category

/**
 * 交易编辑初始值。集中打包多参数，避免 LongParameterList。
 */
data class TransactionEditDialogInitial(
    val amount: Int = 0, // 分
    val type: String = "expense", // expense/income/transfer
    val categoryId: Int? = null,
    val description: String? = null,
    val accountId: Int? = null,
    val targetAccountId: Int? = null,
    val tags: List<String> = emptyList(),
)

/**
 * 通用交易编辑对话框。
 * 首页 AI 确认和通知中心确认统一复用此弹窗。
 * 支持：金额/类型(支出/收入/转账)/分类选择/备注/标签/转账账户选择。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionEditDialog(
    initial: TransactionEditDialogInitial,
    availableTags: List<String> = emptyList(),
    categoriesByType: Map<String, List<Category>>,
    accounts: List<Account> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, type: String, categoryId: Int?, description: String, accountId: Int?, targetAccountId: Int?, tags: List<String>) -> Unit,
) {
    var type by remember { mutableStateOf(initial.type) }
    var amountText by remember { mutableStateOf(if (initial.amount > 0) "%.2f".format(initial.amount / 100.0) else "") }
    var description by remember { mutableStateOf(initial.description ?: "") }
    var selectedCategoryId by remember { mutableStateOf(initial.categoryId) }
    var selectedAccountId by remember { mutableStateOf(initial.accountId) }
    var selectedTargetAccountId by remember { mutableStateOf(initial.targetAccountId) }
    var tags by remember { mutableStateOf(initial.tags) }
    var tagInput by remember { mutableStateOf("") }
    var showTagSuggestions by remember { mutableStateOf(false) }

    val typeKey = if (type == "income") "income" else "expense"
    val amountAccent = when (type) {
        "income" -> MaterialTheme.semantic.income
        "transfer" -> MaterialTheme.semantic.transfer
        else -> MaterialTheme.semantic.expense
    }
    val availableCategories = categoriesByType[typeKey].orEmpty()

    val tagSuggestions = remember(tagInput, availableTags, tags) {
        if (tagInput.isBlank()) availableTags.filter { it !in tags }.take(5)
        else availableTags.filter { it.contains(tagInput, ignoreCase = true) && it !in tags }.take(5)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并确认") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 类型三选：走共享控件，和记账页/筛选区是同一套控件
                TypeSegmentedControl(
                    selected = type,
                    onSelected = { type = it },
                    horizontalPadding = 0.dp,
                )

                // 金额：等宽数字 + 按类型着色，和 App 内其它金额输入保持一致
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                            amountText = newVal
                        }
                    },
                    label = { Text("金额") },
                    placeholder = { Text("0.00", style = AmountTypography.Large) },
                    leadingIcon = {
                        Text(
                            text = "¥",
                            style = AmountTypography.Large.copy(
                                color = amountAccent.copy(alpha = 0.7f),
                            ),
                        )
                    },
                    textStyle = AmountTypography.Large.copy(color = amountAccent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.sm),
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

                // 标签输入区
                Text("标签", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                ) {
                    tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { tags = tags - tag },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "移除标签",
                                    modifier = Modifier.size(Tokens.IconSize.sm),
                                )
                            },
                            modifier = Modifier.height(28.dp),
                        )
                    }
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { value ->
                            tagInput = value
                            showTagSuggestions = true
                        },
                        placeholder = { Text("添加标签", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(Tokens.Radius.sm),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (tagInput.isNotBlank()) {
                                val trimmed = tagInput.trim()
                                if (trimmed !in tags) {
                                    tags = tags + trimmed
                                }
                                tagInput = ""
                                showTagSuggestions = false
                            }
                        }),
                    )
                }
                if (tagSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                    ) {
                        tagSuggestions.forEach { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    tags = tags + suggestion
                                    tagInput = ""
                                    showTagSuggestions = false
                                },
                                label = { Text(suggestion) },
                                modifier = Modifier.height(26.dp),
                            )
                        }
                    }
                }

                if (type != "transfer") {
                    // 分类选择
                    Text("分类", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (availableCategories.isEmpty()) {
                        Text(
                            "这个类型下还没有分类，去「我的 → 分类管理」添加一个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semantic.transfer,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                        ) {
                            availableCategories.forEach { cat ->
                                AppChip(
                                    selected = selectedCategoryId == cat.id,
                                    onClick = { selectedCategoryId = cat.id },
                                    label = "${cat.icon} ${cat.name}",
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
                        accounts = accounts,
                        selectedId = selectedTargetAccountId,
                        onSelect = { selectedTargetAccountId = it },
                        excludeId = selectedAccountId,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(Tokens.Radius.xl),
        confirmButton = {
            PrimaryButton(
                text = "确认入账",
                onClick = {
                    val cents = Math.round((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    val finalCategoryId = if (type == "transfer") null else selectedCategoryId
                    onConfirm(
                        cents, type, finalCategoryId, description,
                        if (type == "transfer") selectedAccountId else null,
                        if (type == "transfer") selectedTargetAccountId else null,
                        tags,
                    )
                },
                enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0,
                tall = false,
            )
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        },
    )
}

@Composable
private fun SimpleAccountPicker(
    label: String,
    accounts: List<Account>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    excludeId: Int? = null,
) {
    com.aibill.android.presentation.components.AccountPicker(
        label = label,
        accounts = accounts,
        selectedId = selectedId,
        onSelect = onSelect,
        placeholder = "选择账户",
        excludeId = excludeId,
    )
}
