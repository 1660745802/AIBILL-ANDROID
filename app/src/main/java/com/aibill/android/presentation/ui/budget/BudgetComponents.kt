package com.aibill.android.presentation.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.data.remote.dto.response.CategoryDto
import com.aibill.android.presentation.theme.AppTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddBudgetDialog(
    categories: List<CategoryDto>,
    isAdding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (categoryId: Int, amount: Int) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<CategoryDto?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val amountCents = amountText.toDoubleOrNull()?.let { (it * 100).toInt() }
    val canConfirm = selectedCategory != null && amountCents != null && amountCents > 0 && !isAdding

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加预算") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(14.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text("${category.icon} ${category.name}") },
                                onClick = {
                                    selectedCategory = category
                                    expanded = false
                                }
                            )
                        }
                    }
                }

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
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            AppTextButton(
                text = "确认",
                onClick = {
                    val catId = selectedCategory?.id ?: return@AppTextButton
                    val cents = amountCents ?: return@AppTextButton
                    onConfirm(catId, cents)
                },
                enabled = canConfirm
            )
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        }
    )
}

@Composable
internal fun TotalBudgetCard(
    totalAmount: Int,
    totalSpent: Int,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val isOverBudget = totalSpent > totalAmount
    val progressColor = if (isOverBudget) Color.Red else MaterialTheme.colorScheme.primary
    val displayProgress = progress.coerceIn(0f, 1f)
    val percentage = if (totalAmount > 0) (totalSpent * 100 / totalAmount) else 0

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "总预算",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // PR #53：标注当前总预算 = 分类预算之和（待后端支持独立总额）
                Text(
                    text = "（分类预算求和）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { displayProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "已用 ${formatAmount(totalSpent)} / ${formatAmount(totalAmount)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${percentage}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = progressColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun BudgetItemCard(
    budget: BudgetDto,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isOverBudget = budget.spent > budget.amount
    val progressColor = if (isOverBudget) Color.Red else MaterialTheme.colorScheme.primary
    val progress = if (budget.amount > 0) {
        (budget.spent.toFloat() / budget.amount).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = budget.categoryName ?: "总预算",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${formatAmount(budget.spent)} / ${formatAmount(budget.amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverBudget) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/**
 * 金额格式化：分 → 元，保留两位小数
 */
internal fun formatAmount(cents: Int): String {
    val yuan = cents / 100.0
    return "¥%.2f".format(yuan)
}
