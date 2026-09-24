package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens

/**
 * 设置页。**重设计**：
 * - 顶部账号卡（点击展开改密）
 * - 5 个语义分组：智能与自动 / 隐私与安全 / 外观 / 数据 / 关于
 * - 所有设置项走 [SettingsCard] + 统一图标/标题/副标题/控件样式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToPermissionGuide: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val hideFromRecents by viewModel.hideFromRecents.collectAsStateWithLifecycle()
    val notificationPrivacy by viewModel.notificationPrivacy.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val quickEntryEnabled by viewModel.quickEntryEnabled.collectAsStateWithLifecycle()
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
        ) {
            // 顶部账号卡
            AccountCard(
                displayName = displayName,
                username = username,
                onClick = { showPasswordDialog = true },
                modifier = Modifier.padding(
                    start = Tokens.Spacing.screenHorizontal,
                    end = Tokens.Spacing.screenHorizontal,
                    top = Tokens.Spacing.md,
                ),
            )

            // ============ 分组1：智能与自动 ============
            SectionLabel(
                title = "智能与自动",
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            )
            SettingsCard(
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            ) {
                // 通知监听状态行（特殊：不是开关，是状态+跳转）
                NotificationStatusRow(
                    granted = uiState.notificationListenerGranted,
                    onNavigate = onNavigateToPermissionGuide,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Default.NotificationsActive,
                    title = "通知栏快捷记账",
                    subtitle = "常驻通知栏，点击快速记一笔",
                    checked = quickEntryEnabled,
                    onCheckedChange = { viewModel.onQuickEntryChanged(it, context) },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Default.Sync,
                    title = "同步记账规则",
                    subtitle = "从服务端拉取最新规则",
                    onClick = { viewModel.syncRules() },
                )
            }

            // ============ 分组2：隐私与安全 ============
            SectionLabel(
                title = "隐私与安全",
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            )
            SettingsCard(
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Default.Lock,
                    title = "应用锁",
                    subtitle = "从后台返回时需要验证身份",
                    checked = appLockEnabled,
                    onCheckedChange = { viewModel.onAppLockChanged(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "通知金额遮罩",
                    subtitle = "通知中金额显示为 ¥***",
                    checked = notificationPrivacy,
                    onCheckedChange = { viewModel.onNotificationPrivacyChanged(it) },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Default.KeyOff,
                    title = "隐藏最近任务",
                    subtitle = "App 不出现在系统最近任务列表",
                    checked = hideFromRecents,
                    onCheckedChange = { viewModel.onHideFromRecentsChanged(it) },
                )
            }

            // ============ 分组3：外观 ============
            SectionLabel(
                title = "外观",
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            )
            SettingsCard(
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            ) {
                Column(modifier = Modifier.padding(Tokens.Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Tokens.IconSize.md),
                        )
                        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "深色模式",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "跟随系统、浅色或深色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Tokens.Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                            FilterChip(
                                selected = themeMode == value,
                                onClick = { viewModel.onThemeChanged(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // ============ 分组4：数据 ============
            SectionLabel(
                title = "数据",
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            )
            SettingsCard(
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            ) {
                SettingsInfoRow(
                    icon = Icons.Default.Dns,
                    title = "服务器地址",
                    value = serverUrl.ifBlank { "未配置" },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Default.Sync,
                    title = "导出日志",
                    subtitle = "生成分享文件给开发者排查",
                    onClick = { viewModel.onExportLogs(context) },
                )
            }

            // ============ 分组5：关于 ============
            SectionLabel(
                title = "关于",
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            )
            SettingsCard(
                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal),
            ) {
                SettingsActionRow(
                    icon = Icons.Default.SystemUpdate,
                    title = "检查更新",
                    subtitle = "查看最新版本",
                    onClick = {
                        viewModel.checkUpdate { result ->
                            if (result is SettingsViewModel.UpdateCheckResult.Available) {
                                pendingUpdate = result.info
                            }
                        }
                    },
                )
            }

            // 底部版本号
            Spacer(modifier = Modifier.height(Tokens.Spacing.md))
            Text(
                text = "AIBILL v${com.aibill.android.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Tokens.Spacing.lg),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Tokens.Spacing.huge))
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

// =============================================================================
// 通用组件
// =============================================================================

/**
 * 顶部账号卡（点击进入改密）。
 * 普通 Card + 头像首字符 + 昵称 + 用户名。
 */
@Composable
private fun AccountCard(
    displayName: String,
    username: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.low),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像首字符（主色调背景）
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.lg)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.firstOrNull()?.toString() ?: "U",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (username.isNotBlank()) {
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "修改密码",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
        }
    }
}

/**
 * 分组小标题。灰色（区别于 primary 强调色）。
 */
@Composable
private fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(start = Tokens.Spacing.xs),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 设置项容器卡。
 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.low),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp, end = Tokens.Spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = Tokens.Border.thin,
    )
}

/**
 * 开关行：图标 + 标题 + 副标题 + Switch。
 */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 操作行：图标 + 标题 + 副标题 + 右箭头。
 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
    }
}

/**
 * 信息行：图标 + 标题 + 值（无箭头，不可点击）。
 */
@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 通知监听状态行（特殊：显示状态文字 + 跳转按钮）。
 */
@Composable
private fun NotificationStatusRow(
    granted: Boolean,
    onNavigate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text("通知监听", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (granted) "已开启，自动记账运行中" else "未授权，请前往设置开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            androidx.compose.material3.TextButton(onClick = onNavigate) {
                Text("前往设置")
            }
        } else {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = "运行中",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(Tokens.IconSize.md),
            )
        }
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
