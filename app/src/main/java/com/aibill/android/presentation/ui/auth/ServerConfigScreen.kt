package com.aibill.android.presentation.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.AppOutlinedButton
import com.aibill.android.presentation.theme.PrimaryButton

@Composable
fun ServerConfigScreen(
    onConfigured: () -> Unit,
    viewModel: ServerConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var pendingClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Emoji 插图
        Text(
            text = "🔗",
            fontSize = 64.sp,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 友好标题
        Text(
            text = "连接你的记账服务器",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "输入你部署的 AIBILL 服务端地址\n让数据安全地保存在你自己的服务器上",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(modifier = Modifier.height(36.dp))

        // 圆角卡片风格输入框
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = viewModel::onUrlChanged,
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: http://192.168.1.100:3000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { viewModel.onTestConnection() }
                    ),
                    isError = uiState.error != null,
                    supportingText = {
                        when {
                            uiState.error != null -> Text(
                                uiState.error!!,
                                color = MaterialTheme.colorScheme.error
                            )
                            uiState.isConnected -> Text(
                                "✅ 连接成功，可以继续了！",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 按钮组
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppOutlinedButton(
                text = "测试连接",
                onClick = { viewModel.onTestConnection() },
                modifier = Modifier.weight(1f),
                enabled = !uiState.isTesting && uiState.serverUrl.isNotBlank(),
                tall = true,
            )

            PrimaryButton(
                text = "继续",
                onClick = {
                    // PR #42：有未同步数据时弹二次确认
                    if (viewModel.hasPendingData()) {
                        // 用全局 state 由 Screen 层捕获显示
                        pendingClearConfirm = true
                    } else {
                        viewModel.onSave(clearLocalCache = true)
                        onConfigured()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = uiState.isConnected,
                icon = if (uiState.isConnected) Icons.Default.Check else null,
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // 底部提示
        Text(
            text = "💡 还没有服务器？查看部署文档快速搭建",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    // PR #42：切换服务器前若有待同步交易，弹 AlertDialog 让用户选择
    if (pendingClearConfirm) {
        AlertDialog(
            onDismissRequest = { pendingClearConfirm = false },
            title = { Text("切换服务器") },
            text = {
                Text("当前有 ${uiState.pendingCount} 条未同步交易。\n切换到新服务器将清空本地缓存。\n确定继续吗？")
            },
            confirmButton = {
                AppTextButton(
                    text = "切换并清空",
                    onClick = {
                        pendingClearConfirm = false
                        viewModel.onSave(clearLocalCache = true)
                        onConfigured()
                    },
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { pendingClearConfirm = false })
            },
        )
    }
}
