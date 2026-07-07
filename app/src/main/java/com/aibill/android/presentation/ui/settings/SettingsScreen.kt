package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.presentation.theme.AppTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToPermissionGuide: () -> Unit = {},
    onNavigateToRecurring: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkNotificationListenerPermission(context)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ========== 分组1：自动记账 ==========
            SettingsSectionLabel("自动记账")

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("通知监听", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (uiState.notificationListenerGranted) "已开启，自动记账运行中" else "未授权，请前往设置开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.notificationListenerGranted) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!uiState.notificationListenerGranted) {
                            TextButton(onClick = onNavigateToPermissionGuide) {
                                Text("前往设置")
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingSwitchRow(
                        title = "通知栏快捷记账",
                        subtitle = "常驻通知栏，点击快速记一笔",
                        checked = uiState.quickEntryEnabled,
                        onCheckedChange = { viewModel.onQuickEntryChanged(it, context) }
                    )
                }
            }
            SettingsNavCard(
                title = "自动记账权限",
                subtitle = "配置通知监听、弹窗、电池优化等权限",
                onClick = onNavigateToPermissionGuide
            )
            SettingsNavCard(
                title = "周期记账",
                subtitle = "设置每月固定收支自动记录",
                onClick = onNavigateToRecurring
            )

            // ========== 分组2：外观 ==========
            SettingsSectionLabel("外观")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "选择跟随系统、始终浅色或始终深色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            // ========== 分组3：隐私与安全 ==========
            SettingsSectionLabel("隐私与安全")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPasswordDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("修改密码", style = MaterialTheme.typography.bodyMedium)
                            Text("更改登录密码", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ========== 分组4：关于 ==========
            SettingsSectionLabel("关于")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("服务器地址", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.serverUrl.ifBlank { "未配置" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onExportLogs(context) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("导出日志", style = MaterialTheme.typography.bodyMedium)
                            Text("排查自动记账问题时分享给开发者", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
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
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = confirmPwd,
                    onValueChange = { confirmPwd = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            AppTextButton(
                text = "确认",
                onClick = { onConfirm(oldPwd, newPwd) },
                enabled = canConfirm
            )
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        }
    )
}
