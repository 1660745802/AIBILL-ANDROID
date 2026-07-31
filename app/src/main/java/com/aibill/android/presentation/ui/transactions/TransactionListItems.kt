package com.aibill.android.presentation.ui.transactions

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import kotlinx.coroutines.launch
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.utils.toYuanDisplay

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

@Composable
internal fun TransactionItem(
    transaction: Transaction,
    onDelete: (Int) -> Unit,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val revealWidth = 72.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealWidthPx = with(density) { revealWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp)),
    ) {
        // 右侧删除按钮（固定在最右边）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(revealWidth)
                .fillMaxHeight()
                .background(Color(0xFFE53935))
                .clickable { transaction.id?.let { onDelete(it) } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "删除",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 前景内容（可左右拖动）
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -revealWidthPx * 0.4f) {
                                    offsetX.animateTo(-revealWidthPx)
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount)
                                    .coerceIn(-revealWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        },
                    )
                }
                .clickable {
                    if (offsetX.value < -10f) {
                        scope.launch { offsetX.animateTo(0f) }
                    } else {
                        onClick()
                    }
                },
            color = MaterialTheme.colorScheme.surface,
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
                            style = MaterialTheme.typography.bodyMedium,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
