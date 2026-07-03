package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToPermissionGuide: () -> Unit = {},
    onNavigateToRecurring: () -> Unit = {},
    onNavigateToAutoRules: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 主题设置
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                            FilterChip(
                                selected = uiState.themeMode == value,
                                onClick = { viewModel.onThemeChanged(value) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // 快捷功能
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("快捷功能", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingSwitchRow(
                        title = "自动记账",
                        subtitle = "监听支付通知并自动记录账单",
                        checked = uiState.notificationEnabled,
                        onCheckedChange = { viewModel.onNotificationEnabledChanged(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingSwitchRow(
                        title = "通知栏快捷记账",
                        subtitle = "常驻通知栏，点击快速记一笔",
                        checked = uiState.quickEntryEnabled,
                        onCheckedChange = { viewModel.onQuickEntryChanged(it, context) }
                    )
                }
            }

            // 周期记账
            SettingsNavCard(
                title = "周期记账",
                subtitle = "设置每月固定收支自动记录",
                onClick = onNavigateToRecurring
            )

            // 智能免确认规则
            SettingsNavCard(
                title = "智能免确认",
                subtitle = "管理自动化规则，让 App 越用越智能",
                onClick = onNavigateToAutoRules
            )

            // 自动记账权限引导
            SettingsNavCard(
                title = "自动记账权限",
                subtitle = "配置通知监听、电池优化等权限",
                onClick = onNavigateToPermissionGuide
            )

            // 隐私 & 安全
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("隐私 & 安全", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingSwitchRow(
                        title = "应用锁",
                        subtitle = "从后台返回时需要验证身份",
                        checked = uiState.appLockEnabled,
                        onCheckedChange = { viewModel.onAppLockChanged(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingSwitchRow(
                        title = "通知隐私模式",
                        subtitle = "通知中金额显示为 ¥***",
                        checked = uiState.notificationPrivacy,
                        onCheckedChange = { viewModel.onNotificationPrivacyChanged(it) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingSwitchRow(
                        title = "在最近任务中隐藏",
                        subtitle = "开启后 App 不出现在系统最近任务列表",
                        checked = uiState.hideFromRecents,
                        onCheckedChange = { viewModel.onHideFromRecentsChanged(it) }
                    )
                }
            }

            // 服务器信息
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.serverUrl.ifBlank { "未配置" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 账号安全
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("账号安全", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showPasswordDialog = true }) {
                        Text("修改密码")
                    }
                }
            }
        }
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            isLoading = uiState.isLoading,
            onDismiss = { showPasswordDialog = false },
            onConfirm = { old, new ->
                viewModel.onChangePassword(old, new)
                showPasswordDialog = false
            }
        )
    }
}

@Composable
private fun SettingsNavCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePasswordDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (oldPassword: String, newPassword: String) -> Unit
) {
    var oldPwd by rememberSaveable { mutableStateOf("") }
    var newPwd by rememberSaveable { mutableStateOf("") }
    var confirmPwd by rememberSaveable { mutableStateOf("") }

    val passwordMismatch = confirmPwd.isNotBlank() && newPwd != confirmPwd
    val canConfirm = oldPwd.isNotBlank() && newPwd.isNotBlank()
            && newPwd == confirmPwd && !isLoading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = oldPwd,
                    onValueChange = { oldPwd = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = confirmPwd,
                    onValueChange = { confirmPwd = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    isError = passwordMismatch,
                    supportingText = if (passwordMismatch) {
                        { Text("两次密码不一致", color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(oldPwd, newPwd) },
                enabled = canConfirm
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
