package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.ui.settings.components.SectionLabel
import com.aibill.android.presentation.ui.settings.components.SettingsActionRow
import com.aibill.android.presentation.ui.settings.components.SettingsNavCard
import com.aibill.android.presentation.ui.settings.components.SettingsSwitchRow

/**
 * 设置页。**已重构**：
 * - 5 个私有 Composable 抽到 [components/SettingsRow.kt]
 * - AppTopBar 复用
 * - ChangePasswordDialog + UpdateDialog 保留（独有）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToPermissionGuide: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var pendingUpdate by remember {
        mutableStateOf<com.aibill.android.service.UpdateManager.UpdateInfo?>(null)
    }

    LaunchedEffect(Unit) {
        viewModel.checkNotificationListenerPermission(context)
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Tokens.Spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            // 分组1：自动记账
            SectionLabel("自动记账")
            SettingsCard {
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
                            color = if (uiState.notificationListenerGranted)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!uiState.notificationListenerGranted) {
                        androidx.compose.material3.TextButton(onClick = onNavigateToPermissionGuide) {
                            Text("前往设置")
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.sm))
                SettingsSwitchRow(
                    title = "通知栏快捷记账",
                    subtitle = "常驻通知栏，点击快速记一笔",
                    checked = uiState.quickEntryEnabled,
                    onCheckedChange = { viewModel.onQuickEntryChanged(it, context) },
                )
            }
            SettingsNavCard(
                title = "自动记账权限",
                subtitle = "配置通知监听、弹窗、电池优化等权限",
                onClick = onNavigateToPermissionGuide,
            )

            // 分组2：外观
            SectionLabel("外观")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Tokens.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "跟随系统、浅色或深色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        FilterChip(
                            selected = uiState.themeMode == value,
                            onClick = { viewModel.onThemeChanged(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 分组3：隐私与安全
            SectionLabel("隐私与安全")
            SettingsCard {
                SettingsSwitchRow(
                    title = "应用锁",
                    subtitle = "从后台返回时需要验证身份",
                    checked = uiState.appLockEnabled,
                    onCheckedChange = { viewModel.onAppLockChanged(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.sm))
                SettingsSwitchRow(
                    title = "通知隐私模式",
                    subtitle = "通知中金额显示为 ¥***",
                    checked = uiState.notificationPrivacy,
                    onCheckedChange = { viewModel.onNotificationPrivacyChanged(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.sm))
                SettingsSwitchRow(
                    title = "在最近任务中隐藏",
                    subtitle = "开启后 App 不出现在系统最近任务列表",
                    checked = uiState.hideFromRecents,
                    onCheckedChange = { viewModel.onHideFromRecentsChanged(it) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.sm))
                SettingsActionRow(
                    title = "修改密码",
                    subtitle = "更改登录密码",
                    onClick = { showPasswordDialog = true },
                )
            }

            // 分组4：数据与同步
            SectionLabel("数据与同步")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Tokens.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("服务器地址", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = uiState.serverUrl.ifBlank { "未配置" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.xs))
                SettingsActionRow(
                    title = "同步规则",
                    subtitle = "从服务端拉取最新通知记账规则",
                    onClick = { viewModel.syncRules() },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = Tokens.Spacing.xs))
                SettingsActionRow(
                    title = "导出日志",
                    subtitle = "生成日志文件分享给开发者排查",
                    onClick = { viewModel.onExportLogs(context) },
                )
            }

            // 分组5：关于
            SectionLabel("关于")
            SettingsCard {
                SettingsActionRow(
                    title = "检查更新",
                    subtitle = "当前版本 ${com.aibill.android.BuildConfig.VERSION_NAME}",
                    onClick = {
                        viewModel.checkUpdate { result ->
                            if (result is SettingsViewModel.UpdateCheckResult.Available) {
                                pendingUpdate = result.info
                            }
                        }
                    },
                )
            }
        }
    }

    // 更新对话框（forceUpdate 时不可取消）
    pendingUpdate?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!info.forceUpdate) pendingUpdate = null },
            title = {
                Text(
                    if (info.forceUpdate) "必须升级到 ${info.versionName}" else "发现新版本 ${info.versionName}",
                    color = if (info.forceUpdate) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column {
                    if (info.forceUpdate) {
                        Text(
                            text = "服务端标记为强制升级，必须升级才能继续使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                    }
                    Text(
                        text = "当前版本 ${com.aibill.android.BuildConfig.VERSION_NAME} → ${info.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (info.changelog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                        Text(
                            text = "更新内容：",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Tokens.Spacing.xs))
                        Text(
                            text = info.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                        )
                    }
                    if (info.apkSize > 0) {
                        Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                        Text(
                            text = "下载大小：${info.apkSize / 1024 / 1024} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            },
            confirmButton = {
                AppTextButton(
                    text = if (info.forceUpdate) "立即升级" else "立即更新",
                    onClick = {
                        viewModel.startUpdateDownload(context, info)
                        pendingUpdate = null
                    },
                )
            },
            dismissButton = if (info.forceUpdate) null else {
                { AppTextButton(text = "稍后", onClick = { pendingUpdate = null }) }
            },
        )
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            isLoading = uiState.isLoading,
            onDismiss = { showPasswordDialog = false },
            onConfirm = { old, new ->
                viewModel.onChangePassword(old, new)
                showPasswordDialog = false
            },
        )
    }
}

/**
 * 设置项容器卡（多个设置项的分组）。
 * 比 [com.aibill.android.presentation.theme.Tokens.Radius.lg] 略大，呼应卡片分组语义。
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(Tokens.Spacing.lg), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePasswordDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (oldPassword: String, newPassword: String) -> Unit,
) {
    var oldPwd by rememberSaveable { mutableStateOf("") }
    var newPwd by rememberSaveable { mutableStateOf("") }
    var confirmPwd by rememberSaveable { mutableStateOf("") }

    val passwordMismatch = confirmPwd.isNotBlank() && newPwd != confirmPwd
    val canConfirm = oldPwd.isNotBlank() && newPwd.isNotBlank() &&
            newPwd == confirmPwd && !isLoading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                OutlinedTextField(
                    value = oldPwd,
                    onValueChange = { oldPwd = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.md),
                )
                OutlinedTextField(
                    value = newPwd,
                    onValueChange = { newPwd = it },
                    label = { Text("新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.md),
                )
                OutlinedTextField(
                    value = confirmPwd,
                    onValueChange = { confirmPwd = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.Radius.md),
                    isError = passwordMismatch,
                    supportingText = if (passwordMismatch) {
                        { Text("两次密码不一致", color = MaterialTheme.colorScheme.error) }
                    } else null,
                )
            }
        },
        confirmButton = {
            AppTextButton(text = "确认", onClick = { onConfirm(oldPwd, newPwd) }, enabled = canConfirm)
        },
        dismissButton = { AppTextButton(text = "取消", onClick = onDismiss) },
    )
}
