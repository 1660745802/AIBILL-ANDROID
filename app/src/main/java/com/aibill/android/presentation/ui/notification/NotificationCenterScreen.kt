package com.aibill.android.presentation.ui.notification

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.ConfirmDialog
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知中心。**已重构**：
 * - 删除死代码 [NotificationEditDialog]（已被 common/TransactionEditDialog 取代）
 * - 使用 [AppTopBar] + [ConfirmDialog] + [EmptyState]
 * - 使用 [AmountText] 统一金额显示
 */
@Composable
fun NotificationCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: NotificationCenterViewModel = hiltViewModel(),
) {
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var ignoreConfirmId by remember { mutableStateOf<Long?>(null) }
    var editItem by remember { mutableStateOf<NotificationRecordEntity?>(null) }
    var expandedConfirmed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is NotificationCenterViewModel.UiEvent.ShowToast ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // 确认前编辑对话框（复用 common 的）
    editItem?.let { item ->
        val categoriesByType by viewModel.categoriesByType.collectAsStateWithLifecycle()
        val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
        com.aibill.android.presentation.ui.common.TransactionEditDialog(
            initialAmount = item.parsedAmount ?: 0,
            initialType = item.parsedType ?: "expense",
            initialCategoryId = null,
            initialDescription = item.parsedDescription,
            categoriesByType = categoriesByType,
            availableTags = availableTags,
            accounts = emptyList(),
            onDismiss = { editItem = null },
            onConfirm = { amount, type, categoryId, desc, accountId, targetAccountId, tags ->
                viewModel.confirmWithEdit(item.id, type, amount, desc, categoryId, tags)
                editItem = null
            },
        )
    }

    if (ignoreConfirmId != null) {
        ConfirmDialog(
            title = "确认忽略",
            message = "忽略后该通知将不会被记录为账单，确定忽略吗？",
            confirmText = "忽略",
            onConfirm = {
                ignoreConfirmId?.let { viewModel.ignoreItem(it) }
                ignoreConfirmId = null
            },
            onDismiss = { ignoreConfirmId = null },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(titleContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("通知中心")
                    if (pendingCount > 0) {
                        Spacer(Modifier.width(Tokens.Spacing.sm))
                        BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "待确认通知",
                            )
                        }
                    }
                }
            }, onBack = onBack)
        },
    ) { paddingValues ->
        val nlsConnected by viewModel.nlsConnected.collectAsStateWithLifecycle()
        val allItems by viewModel.allNotifications.collectAsStateWithLifecycle()

        if (allItems.isEmpty()) {
            EmptyState(
                emoji = "🔔",
                title = "暂无待确认通知",
                subtitle = "支付通知到达后会显示在这里",
                modifier = Modifier.padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = Tokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
            ) {
                item(key = "health_status") {
                    NlsHealthCard(isConnected = nlsConnected, pendingCount = pendingCount)
                    Spacer(Modifier.height(Tokens.Spacing.xs))
                }

                val pendingList = allItems.filter { it.status in listOf("raw", "parsed") }
                val confirmedList = allItems.filter { it.status !in listOf("raw", "parsed") }

                items(pendingList, key = { it.id }) { item ->
                    NotificationItem(
                        item = item,
                        onConfirm = { editItem = item },
                        onIgnore = { ignoreConfirmId = item.id },
                    )
                }

                if (confirmedList.isNotEmpty()) {
                    item(key = "confirmed_summary") {
                        ConfirmedSummaryCard(
                            count = confirmedList.size,
                            totalAmount = confirmedList.sumOf { it.parsedAmount ?: 0 },
                            expanded = expandedConfirmed,
                            onToggle = { expandedConfirmed = !expandedConfirmed },
                        )
                    }
                    if (expandedConfirmed) {
                        items(confirmedList, key = { "confirmed_${it.id}" }) { item ->
                            ConfirmedNotificationItem(item = item)
                        }
                    }
                }

                item { Spacer(Modifier.height(Tokens.Spacing.lg)) }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    item: NotificationRecordEntity,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "通知来源",
                modifier = Modifier.size(Tokens.Avatar.md),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Tokens.Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.parsedAmount?.let {
                        "¥${"%.2f".format(it / 100.0)}"
                    } ?: "未识别金额",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.parsedDescription ?: item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${com.aibill.android.util.NotificationSourceMapping.friendlyName(item.packageName)} · ${formatTime(item.receivedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Tokens.Spacing.sm))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(text = "确认", onClick = onConfirm, tall = false)
                Spacer(Modifier.height(Tokens.Spacing.xs))
                AppTextButton(text = "忽略", onClick = onIgnore)
            }
        }
    }
}

@Composable
private fun NlsHealthCard(isConnected: Boolean, pendingCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (isConnected) "🟢" else "🔴", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(Tokens.Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) "通知监听正常运行" else "通知监听已断开",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (isConnected) "待确认 $pendingCount 条" else "请前往系统设置重新开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmedSummaryCard(
    count: Int,
    totalAmount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(Tokens.Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "✓ 已记录 $count 笔",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "支出 ¥${"%.2f".format(totalAmount / 100.0)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Tokens.Spacing.xs))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(Tokens.IconSize.sm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmedNotificationItem(item: NotificationRecordEntity) {
    val timeText = remember(item.receivedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receivedAt))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.parsedDescription ?: item.title ?: "自动记录",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$timeText · 已入库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.parsedAmount != null && item.parsedType != null) {
                AmountText(
                    amount = item.parsedAmount,
                    type = when (item.parsedType) {
                        "income" -> com.aibill.android.domain.model.TransactionType.INCOME
                        else -> com.aibill.android.domain.model.TransactionType.EXPENSE
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
