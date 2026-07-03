package com.aibill.android.presentation.ui.trash

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.utils.toYuanDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "🗑️", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "回收站是空的",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        TrashItem(
                            transaction = item,
                            onRestore = { viewModel.restoreTransaction(item.id) },
                            onPermanentDelete = { deleteConfirmId = item.id },
                        )
                    }
                }
            }
        }
    }

    // 永久删除确认弹窗
    deleteConfirmId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("永久删除") },
            text = { Text("确定永久删除这笔记录吗？此操作不可恢复。") },
            confirmButton = {
                AppTextButton(
                    text = "删除",
                    onClick = {
                        viewModel.permanentDelete(id)
                        deleteConfirmId = null
                    },
                    isDestructive = true,
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deleteConfirmId = null })
            }
        )
    }
}

@Composable
private fun TrashItem(
    transaction: TransactionDto,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分类图标
            Text(
                text = transaction.categoryIcon ?: "📝",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 描述 + 日期
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.description
                        ?: transaction.categoryName
                        ?: "未分类",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = transaction.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 金额
            val prefix = if (transaction.type == "expense") "-" else "+"
            Text(
                text = "$prefix${transaction.amount.toYuanDisplay()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (transaction.type == "expense") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 恢复按钮
            IconButton(onClick = onRestore, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.RestoreFromTrash,
                    contentDescription = "恢复",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 永久删除按钮
            IconButton(onClick = onPermanentDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = "永久删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
