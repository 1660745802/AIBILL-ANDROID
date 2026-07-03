package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.utils.toYuanDisplay

internal val ExpenseColor = Color(0xFFF44336)
internal val IncomeColor = Color(0xFF4CAF50)

@Composable
internal fun DateHeader(
    date: String,
    transactions: List<Transaction>,
    modifier: Modifier = Modifier,
) {
    val expenseTotal = transactions
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { it.amount }
    val incomeTotal = transactions
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amount }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expenseTotal > 0) {
                Text(
                    text = "支出 ${expenseTotal.toYuanDisplay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ExpenseColor,
                )
            }
            if (incomeTotal > 0) {
                Text(
                    text = "收入 ${incomeTotal.toYuanDisplay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = IncomeColor,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionItem(
    transaction: Transaction,
    onDelete: (Int) -> Unit,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                showDeleteDialog = true
            }
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
        modifier = modifier.clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 圆形图标徽章
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
                        fontSize = 20.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 中间：分类名 + 描述
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.categoryName ?: "未分类",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!transaction.description.isNullOrBlank()) {
                    Text(
                        text = transaction.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧：金额 + 时间
            Column(horizontalAlignment = Alignment.End) {
                val amountColor = when (transaction.type) {
                    TransactionType.EXPENSE -> ExpenseColor
                    TransactionType.INCOME -> IncomeColor
                    TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurface
                }
                val prefix = when (transaction.type) {
                    TransactionType.EXPENSE -> "-"
                    TransactionType.INCOME -> "+"
                    TransactionType.TRANSFER -> ""
                }
                Text(
                    text = "$prefix${transaction.amount.toYuanDisplay()}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
                transaction.time?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("确定删除这笔记录吗？删除后可在回收站恢复。") },
            shape = RoundedCornerShape(20.dp),
            confirmButton = {
                AppTextButton(
                    text = "删除",
                    onClick = {
                        showDeleteDialog = false
                        transaction.id?.let { onDelete(it) }
                    }
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { showDeleteDialog = false })
            },
        )
    }
}
