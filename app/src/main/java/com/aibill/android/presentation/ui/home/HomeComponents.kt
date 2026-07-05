package com.aibill.android.presentation.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.AiParseResult
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.utils.toYuanDisplay

@Composable
internal fun EmptyTodayCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "📝", fontSize = 40.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "今天还没有记录哦",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "试试输入「午餐 15」快速记一笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
internal fun AiResultsCard(
    results: List<AiParseResult>,
    categoriesByType: Map<String, List<com.aibill.android.domain.model.Category>>,
    onConfirmItem: (AiParseResult) -> Unit,
    onConfirmAll: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmEdited: (AiParseResult, Int, TransactionType, Int, String) -> Unit = { _, _, _, _, _ -> },
) {
    var editTarget by remember { mutableStateOf<AiParseResult?>(null) }
    editTarget?.let { target ->
        AiEditDialog(
            item = target,
            categoriesByType = categoriesByType,
            onDismiss = { editTarget = null },
            onConfirm = { amount, type, categoryId, desc ->
                onConfirmEdited(target, amount, type, categoryId, desc)
                editTarget = null
            }
        )
    }
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("🤖", fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI 识别到 ${results.size} 笔",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "点击任意一笔可修改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                results.forEach { item ->
                    ParseResultItem(
                        item = item,
                        onConfirm = { onConfirmItem(item) },
                        onEdit = { editTarget = item },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (results.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    PrimaryButton(
                        text = "全部确认 (${results.size} 笔)",
                        onClick = onConfirmAll,
                        icon = Icons.Default.Check,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ParseResultItem(item: AiParseResult, onConfirm: () -> Unit, onEdit: () -> Unit = {}) {
    val isIncome = item.type == TransactionType.INCOME
    val accent = if (isIncome) IncomeColor else ExpenseColor
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = item.categoryIcon ?: "📝", fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.categoryName ?: "未分类",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (!item.description.isNullOrBlank()) item.description else "点击编辑备注",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isIncome) "+${item.amount.toYuanDisplay()}" else "-${item.amount.toYuanDisplay()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Spacer(modifier = Modifier.width(10.dp))
            // 确认对勾按钮
            Surface(
                onClick = onConfirm,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "确认",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AiEditDialog(
    item: AiParseResult,
    categoriesByType: Map<String, List<com.aibill.android.domain.model.Category>>,
    onDismiss: () -> Unit,
    onConfirm: (amount: Int, type: TransactionType, categoryId: Int, description: String) -> Unit,
) {
    var type by remember { mutableStateOf(item.type) }
    var amountText by remember { mutableStateOf("%.2f".format(item.amount / 100.0)) }
    var description by remember { mutableStateOf(item.description ?: "") }
    // 当前选中的分类 id，初值为 AI 给的；切换 type 时重置
    var selectedCategoryId by remember { mutableStateOf(item.categoryId) }

    val typeKey = if (type == TransactionType.EXPENSE) "expense" else "income"
    val availableCategories = categoriesByType[typeKey].orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TransactionType.EXPENSE,
                        onClick = {
                            type = TransactionType.EXPENSE
                            // 重置为该类型下第一个分类
                            selectedCategoryId = availableCategories.firstOrNull()?.id ?: selectedCategoryId
                        },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = type == TransactionType.INCOME,
                        onClick = {
                            type = TransactionType.INCOME
                            selectedCategoryId = availableCategories.firstOrNull()?.id ?: selectedCategoryId
                        },
                        label = { Text("收入") }
                    )
                }
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
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryChipFlow(
                    availableCategories = availableCategories,
                    selectedCategoryId = selectedCategoryId ?: 0,
                    onSelect = { selectedCategoryId = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = Math.round((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    // 兜底：若用户没动分类但原 AI 分类 id 不在新分类列表，用列表第一个
                    val finalCategoryId: Int = availableCategories
                        .firstOrNull { it.id == selectedCategoryId }
                        ?.id
                        ?: availableCategories.firstOrNull()?.id
                        ?: selectedCategoryId
                        ?: 0
                    onConfirm(cents, type, finalCategoryId, description)
                },
                enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0
            ) { Text("确认记账") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryChipFlow(
    availableCategories: List<com.aibill.android.domain.model.Category>,
    selectedCategoryId: Int,
    onSelect: (Int) -> Unit,
) {
    if (availableCategories.isEmpty()) {
        Text(
            text = "暂无可选分类",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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

@Composable
internal fun TransactionItem(transaction: Transaction, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = transaction.categoryIcon ?: "📝",
                        fontSize = 22.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.categoryName ?: "未分类",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (!transaction.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = transaction.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = if (transaction.type == TransactionType.INCOME) {
                    "+${transaction.amount.toYuanDisplay()}"
                } else {
                    "-${transaction.amount.toYuanDisplay()}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (transaction.type == TransactionType.INCOME) {
                    IncomeColor
                } else {
                    ExpenseColor
                },
            )
        }
    }
}
