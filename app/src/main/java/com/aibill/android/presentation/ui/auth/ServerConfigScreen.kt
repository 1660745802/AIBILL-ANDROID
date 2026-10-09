package com.aibill.android.presentation.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.aibill.android.presentation.theme.semantic
import com.aibill.android.presentation.ui.auth.components.AuthScaffold
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.AppOutlinedButton
import com.aibill.android.presentation.theme.PrimaryButton

@Composable
fun ServerConfigScreen(
    onConfigured: () -> Unit,
    viewModel: ServerConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingClearConfirm by rememberSaveable { mutableStateOf(false) }

    AuthScaffold(
        title = "连接你的记账服务器",
        subtitle = "填入你部署的 AIBILL 服务端地址，数据会保存在自己的服务器上",
    ) {
        // 圆角卡片风格输入框
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.lg),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(Tokens.Spacing.lg)) {
                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = viewModel::onUrlChanged,
                    label = { Text("服务器地址") },
                    placeholder = { Text("例如: http://192.168.1.100:3000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.sm),
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
                            uiState.isConnected -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement =
                                    androidx.compose.foundation.layout.Arrangement.spacedBy(
                                        Tokens.Spacing.xs,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.semantic.success,
                                    modifier = Modifier.size(Tokens.IconSize.xs),
                                )
                                Text(
                                    "连接成功，可以继续",
                                    color = MaterialTheme.semantic.success,
                                )
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.xl))

        // 按钮组
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)
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

        Spacer(modifier = Modifier.height(Tokens.Spacing.lg))

        // 底部提示：emoji 换成矢量图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement.spacedBy(Tokens.Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
            Text(
                text = "还没有服务器？参照部署文档在本地或云主机上跑一个，只要几分钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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
