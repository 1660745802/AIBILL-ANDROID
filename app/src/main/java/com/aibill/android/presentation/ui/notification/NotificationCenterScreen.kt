package com.aibill.android.presentation.ui.notification

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.util.NotificationSourceMapping
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.ConfirmDialog
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.AppOutlinedButton
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
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
            initial = com.aibill.android.presentation.ui.common.TransactionEditDialogInitial(
                amount = item.parsedAmount ?: 0,
                type = item.parsedType ?: "expense",
                description = item.parsedDescription,
            ),
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
                icon = Icons.Outlined.NotificationsNone,
                title = "没有待确认的通知",
                subtitle = "支付后收到的通知会出现在这里，确认一下就入账",
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
    val amount = item.parsedAmount

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Tokens.Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 来源 App 的 emoji 图标 —— 这是通知内容自带的，保留但弱化尺寸
            Text(
                text = item.content.substringBefore(" ").take(1).ifBlank { "🔔" },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(Tokens.Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = NotificationSourceMapping.friendlyName(item.packageName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.parsedDescription ?: item.title ?: "自动识别到的交易",
                    style = AppTextStyles.ListTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Tokens.Spacing.sm))
            // 金额是这条通知里最重要的信息，右对齐并按类型着色
            // 只要解析出金额就展示。类型缺失（解析器把非法 type 置空）时
            // 回落到中性色，而不是把已识别出的金额降级成「待识别」。
            if (amount != null) {
                AmountText(
                    amount = amount,
                    type = when (item.parsedType) {
                        "income" -> TransactionType.INCOME
                        "transfer" -> TransactionType.TRANSFER
                        "expense" -> TransactionType.EXPENSE
                        else -> null
                    },
                    style = AmountTypography.Large,
                    showSign = false,
                )
            } else {
                Text(
                    text = "待识别",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(Modifier.height(Tokens.Spacing.sm))
        Text(
            text = formatTime(item.receivedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Spacer(Modifier.height(Tokens.Spacing.md))

        // 操作横排：改造前「确认」和「忽略」竖排挤在右侧一个窄列里，
        // 两个按钮都不足 48dp 宽。现在改成底部整行，触控面积达标且主次分明。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppOutlinedButton(
                text = "忽略",
                onClick = onIgnore,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "确认入账",
                onClick = onConfirm,
                modifier = Modifier.weight(2f),
                tall = false,
            )
        }
    }
}

/**
 * 通知监听健康状态横幅。
 *
 * 改造前用 🟢 / 🔴 两个 emoji 表示状态 —— 这是典型的「用颜色 + 图形猜意思」，
 * 色弱用户完全读不出来。现在换成矢量图标（心跳 / 断开）+ 文字状态 + 修复入口。
 */
@Composable
private fun NlsHealthCard(isConnected: Boolean, pendingCount: Int) {
    val semantics = MaterialTheme.semantic
    val container = if (isConnected) semantics.successContainer else semantics.dangerContainer
    val fg = if (isConnected) MaterialTheme.semantic.income else semantics.danger

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(container)
            .padding(Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.CheckCircle
            else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(Modifier.width(Tokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isConnected) "通知监听运行中" else "通知监听已断开",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            Text(
                text = if (isConnected) {
                    if (pendingCount > 0) "$pendingCount 条等待你确认" else "新的支付通知会自动识别"
                } else {
                    "自动记账已停止，去系统设置里重新开启"
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * 已记录区折叠头。
 */
@Composable
private fun ConfirmedSummaryCard(
    count: Int,
    totalAmount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.semantic.income,
            modifier = Modifier.size(Tokens.IconSize.sm),
        )
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Text(
            text = "已自动记录 $count 笔",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AmountText(
            amount = totalAmount,
            type = TransactionType.EXPENSE,
            style = AmountTypography.Chip,
            showSign = false,
        )
        Spacer(Modifier.width(Tokens.Spacing.xs))
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "收起已记录" else "展开已记录",
            modifier = Modifier.size(Tokens.IconSize.sm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 已入账的单条通知（折叠区里）。弱化为一行，不再是一张卡。
 */
@Composable
private fun ConfirmedNotificationItem(item: NotificationRecordEntity) {
    val timeText = remember(item.receivedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receivedAt))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.parsedDescription ?: item.title ?: "自动记录",
                style = AppTextStyles.ListSubtitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$timeText · ${NotificationSourceMapping.friendlyName(item.packageName)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(Tokens.Spacing.sm))
        if (item.parsedAmount != null && item.parsedType != null) {
            AmountText(
                amount = item.parsedAmount,
                type = when (item.parsedType) {
                    "income" -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                },
                style = AmountTypography.Stat,
                showSign = false,
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
