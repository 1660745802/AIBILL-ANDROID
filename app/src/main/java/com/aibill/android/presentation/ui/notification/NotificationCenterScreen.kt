package com.aibill.android.presentation.ui.notification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.presentation.theme.AppOutlinedButton
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.PrimaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: NotificationCenterViewModel = hiltViewModel()
) {
    val pendingItems by viewModel.pendingNotifications.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val isConfirming by viewModel.isConfirming.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var ignoreConfirmId by remember { mutableStateOf<Long?>(null) }
    var editItem by remember { mutableStateOf<NotificationRecordEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is NotificationCenterViewModel.UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // 确认前编辑对话框
    editItem?.let { item ->
        val categoriesByType by viewModel.categoriesByType.collectAsStateWithLifecycle()
        com.aibill.android.presentation.ui.common.TransactionEditDialog(
            initialAmount = item.parsedAmount ?: 0,
            initialType = item.parsedType ?: "expense",
            initialCategoryId = null,
            initialDescription = item.parsedDescription,
            categoriesByType = categoriesByType,
            accounts = emptyList(), // TODO: 注入账户列表
            onDismiss = { editItem = null },
            onConfirm = { amount, type, categoryId, desc, accountId, targetAccountId ->
                viewModel.confirmWithEdit(item.id, type, amount, desc, categoryId)
                editItem = null
            }
        )
    }

    // Ignore confirmation dialog
    if (ignoreConfirmId != null) {
        AlertDialog(
            onDismissRequest = { ignoreConfirmId = null },
            title = { Text("确认忽略") },
            text = { Text("忽略后该通知将不会被记录为账单，确定忽略吗？") },
            confirmButton = {
                AppTextButton(text = "忽略", onClick = {
                    ignoreConfirmId?.let { viewModel.ignoreItem(it) }
                    ignoreConfirmId = null
                })
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { ignoreConfirmId = null })
            }
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知中心")
                        if (pendingCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "待确认通知"
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (pendingItems.isNotEmpty()) {
                PrimaryButton(
                    text = if (isConfirming) "确认中..." else "全部确认",
                    onClick = { viewModel.confirmAll() },
                    enabled = !isConfirming,
                    loading = isConfirming,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    ) { paddingValues ->
        val nlsConnected by viewModel.nlsConnected.collectAsStateWithLifecycle()
        val allItems by viewModel.allNotifications.collectAsStateWithLifecycle()

        if (allItems.isEmpty() && pendingItems.isEmpty()) {
            EmptyState(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 健康度面板
                item(key = "health_status") {
                    NlsHealthCard(
                        isConnected = nlsConnected,
                        pendingCount = pendingCount,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // 按时间混排：已入库和待确认在一个列表
                if (allItems.isEmpty() && !pendingItems.isNotEmpty()) {
                    item(key = "empty") { EmptyState() }
                } else {
                    items(allItems, key = { it.id }) { item ->
                        val isPending = item.status in listOf("raw", "parsed")
                        if (isPending) {
                            NotificationItem(
                                item = item,
                                onConfirm = { editItem = item },
                                onIgnore = { ignoreConfirmId = item.id }
                            )
                        } else {
                            ConfirmedNotificationItem(item = item)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    item: NotificationRecordEntity,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 来源图标
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "通知来源",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 内容区域
            Column(modifier = Modifier.weight(1f)) {
                // 金额
                val amountText = item.parsedAmount?.let {
                    "¥%.2f".format(it / 100.0)
                } ?: "未识别金额"

                Text(
                    text = amountText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // 描述
                val desc = item.parsedDescription ?: item.content
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 时间
                Text(
                    text = formatTime(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 操作按钮
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(
                    text = "确认",
                    onClick = onConfirm,
                )
                Spacer(modifier = Modifier.height(4.dp))
                AppOutlinedButton(
                    text = "忽略",
                    onClick = onIgnore,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🔔",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无待确认通知",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "支付通知到达后会显示在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}


@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NotificationEditDialog(
    item: NotificationRecordEntity,
    categoriesByType: Map<String, List<com.aibill.android.domain.model.Category>>,
    onDismiss: () -> Unit,
    onConfirm: (type: String, amountCents: Int, description: String, categoryId: Int?) -> Unit,
) {
    var type by remember { mutableStateOf(item.parsedType ?: "expense") }
    var amountText by remember {
        mutableStateOf(
            item.parsedAmount?.let { "%.2f".format(it / 100.0) } ?: ""
        )
    }
    var description by remember { mutableStateOf(item.parsedDescription ?: "") }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }

    val availableCategories = categoriesByType[type].orEmpty()

    // 初始化或切换类型时，选中第一个分类
    LaunchedEffect(type, availableCategories) {
        if (selectedCategoryId == null || availableCategories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = availableCategories.firstOrNull()?.id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑并确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 类型切换
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = type == "expense",
                        onClick = {
                            type = "expense"
                            selectedCategoryId = categoriesByType["expense"]?.firstOrNull()?.id
                        },
                        label = { Text("支出") }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = type == "income",
                        onClick = {
                            type = "income"
                            selectedCategoryId = categoriesByType["income"]?.firstOrNull()?.id
                        },
                        label = { Text("收入") }
                    )
                }
                // 金额输入
                androidx.compose.material3.OutlinedTextField(
                    value = amountText,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                            amountText = newVal
                        }
                    },
                    label = { Text("金额 (元)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                )
                // 描述输入
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("备注") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 分类选择
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (availableCategories.isEmpty()) {
                    Text(
                        text = "暂无可选分类",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        availableCategories.forEach { cat ->
                            androidx.compose.material3.FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = { selectedCategoryId = cat.id },
                                label = { Text("${cat.icon} ${cat.name}") },
                            )
                        }
                    }
                }
                // 原始通知内容参考
                Text(
                    text = "原文：${item.content.take(60)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            AppTextButton(
                text = "确认记账",
                onClick = {
                    val cents = Math.round((amountText.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    val finalCategoryId = availableCategories
                        .firstOrNull { it.id == selectedCategoryId }
                        ?.id
                        ?: availableCategories.firstOrNull()?.id
                    onConfirm(type, cents, description, finalCategoryId)
                },
                enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0
            )
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        }
    )
}

@Composable
private fun NlsHealthCard(
    isConnected: Boolean,
    pendingCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isConnected) "🟢" else "🔴",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
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
private fun ConfirmedNotificationItem(item: NotificationRecordEntity) {
    val timeText = remember(item.receivedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.receivedAt))
    }
    val amountText = item.parsedAmount?.let {
        val sign = if (item.parsedType == "income") "+" else "-"
        "$sign¥${"%.2f".format(it / 100.0)}"
    } ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
            if (amountText.isNotBlank()) {
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.parsedType == "income") {
                        com.aibill.android.presentation.theme.IncomeColor
                    } else {
                        com.aibill.android.presentation.theme.ExpenseColor
                    },
                )
            }
        }
    }
}
