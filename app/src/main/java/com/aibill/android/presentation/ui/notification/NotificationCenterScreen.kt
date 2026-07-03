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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    val pendingItems by viewModel.pendingNotifications.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var ignoreConfirmId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is NotificationCenterViewModel.UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    // Ignore confirmation dialog
    if (ignoreConfirmId != null) {
        AlertDialog(
            onDismissRequest = { ignoreConfirmId = null },
            title = { Text("确认忽略") },
            text = { Text("忽略后该通知将不会被记录为账单，确定忽略吗？") },
            confirmButton = {
                TextButton(onClick = {
                    ignoreConfirmId?.let { viewModel.ignoreItem(it) }
                    ignoreConfirmId = null
                }) { Text("忽略") }
            },
            dismissButton = {
                TextButton(onClick = { ignoreConfirmId = null }) { Text("取消") }
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
                Button(
                    onClick = { viewModel.confirmAll() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("全部确认")
                }
            }
        }
    ) { paddingValues ->
        if (pendingItems.isEmpty()) {
            EmptyState(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pendingItems, key = { it.id }) { item ->
                    NotificationItem(
                        item = item,
                        onConfirm = { viewModel.confirmItem(item.id) },
                        onIgnore = { ignoreConfirmId = item.id }
                    )
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
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("确认", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("忽略", style = MaterialTheme.typography.labelSmall)
                }
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
