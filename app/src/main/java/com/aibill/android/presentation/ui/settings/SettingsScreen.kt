package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.GroupedDivider
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.GroupedRow
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.components.SegmentedControl
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 设置页。**重设计**：
 * - 顶部账号卡（点击展开改密），作为唯一的视觉重点
 * - 语义分组：账号 / 智能与自动 / 隐私与安全 / 外观 / 数据 / 关于
 * - 所有行走共享 [GroupedList] / [GroupedRow]，组标题走共享 [SectionHeader]（中性灰）
 * - 主题切换用共享 [SegmentedControl]（替代 FilterChip）
 * - 通知监听状态用 [GroupedRow] 的 trailing 槽放「色点 + 箭头」（替代 Switch）
 * - 检查更新带 loading 态
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
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val hideFromRecents by viewModel.hideFromRecents.collectAsStateWithLifecycle()
    val notificationPrivacy by viewModel.notificationPrivacy.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val quickEntryEnabled by viewModel.quickEntryEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var pendingUpdate by remember {
        mutableStateOf<com.aibill.android.service.UpdateManager.UpdateInfo?>(null)
    }

    LaunchedEffect(Unit) {
        viewModel.checkNotificationListenerPermission(context)
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val sidePadding = Modifier.padding(horizontal = Tokens.Spacing.screenHorizontal)

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
            // ============ 账号（唯一视觉重点） ============
            SectionHeader(title = "账号", modifier = sidePadding.padding(top = Tokens.Spacing.md))
            GroupedList(modifier = sidePadding) {
                AccountRow(
                    displayName = displayName,
                    username = username,
                    onClick = { showPasswordDialog = true },
                )
            }

            // ============ 智能与自动 ============
            SectionHeader(title = "智能与自动", modifier = sidePadding)
            GroupedList(modifier = sidePadding) {
                // 通知监听状态行（状态 + 跳转，不是开关）
                NotificationStatusRow(
                    granted = uiState.notificationListenerGranted,
                    onNavigate = onNavigateToPermissionGuide,
                )
                GroupedDivider()
                SwitchRow(
                    icon = Icons.Default.NotificationsActive,
                    title = "通知栏快捷记账",
                    subtitle = "常驻通知栏，点击快速记一笔",
                    checked = quickEntryEnabled,
                    onCheckedChange = { viewModel.onQuickEntryChanged(it, context) },
                )
                GroupedDivider()
                GroupedRow(
                    title = "同步记账规则",
                    subtitle = "从服务端拉取最新规则",
                    icon = Icons.Default.Sync,
                    onClick = { viewModel.syncRules() },
                )
            }

            // ============ 隐私与安全 ============
            SectionHeader(title = "隐私与安全", modifier = sidePadding)
            GroupedList(modifier = sidePadding) {
                SwitchRow(
                    icon = Icons.Default.Lock,
                    title = "应用锁",
                    subtitle = "从后台返回时需要验证身份",
                    checked = appLockEnabled,
                    onCheckedChange = { viewModel.onAppLockChanged(it) },
                )
                GroupedDivider()
                SwitchRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "通知金额遮罩",
                    subtitle = "通知中金额显示为 ¥***",
                    checked = notificationPrivacy,
                    onCheckedChange = { viewModel.onNotificationPrivacyChanged(it) },
                )
                GroupedDivider()
                SwitchRow(
                    icon = Icons.Default.KeyOff,
                    title = "隐藏最近任务",
                    subtitle = "App 不出现在系统最近任务列表",
                    checked = hideFromRecents,
                    onCheckedChange = { viewModel.onHideFromRecentsChanged(it) },
                )
            }

            // ============ 外观 ============
            SectionHeader(title = "外观", modifier = sidePadding)
            GroupedList(modifier = sidePadding) {
                ThemeRow(
                    themeMode = themeMode,
                    onThemeChanged = { viewModel.onThemeChanged(it) },
                )
                GroupedDivider()
                SwitchRow(
                    icon = Icons.Default.ColorLens,
                    title = "跟随壁纸配色",
                    subtitle = "Android 12+ 取壁纸色替换品牌色；开启后记账语义色仍保持不变",
                    checked = dynamicColorEnabled,
                    onCheckedChange = { viewModel.onDynamicColorChanged(it) },
                )
            }

            // ============ 数据 ============
            SectionHeader(title = "数据", modifier = sidePadding)
            GroupedList(modifier = sidePadding) {
                InfoRow(
                    icon = Icons.Default.Dns,
                    title = "服务器地址",
                    value = serverUrl.ifBlank { "未配置" },
                )
                GroupedDivider()
                GroupedRow(
                    title = "导出日志",
                    subtitle = "生成分享文件给开发者排查",
                    icon = Icons.Default.Share,
                    onClick = { viewModel.onExportLogs(context) },
                )
            }

            // ============ 关于 ============
            SectionHeader(title = "关于", modifier = sidePadding)
            GroupedList(modifier = sidePadding) {
                GroupedRow(
                    title = "检查更新",
                    subtitle = if (checkingUpdate) "正在获取最新版本…" else "查看最新版本",
                    icon = Icons.Default.SystemUpdate,
                    onClick = {
                        if (!checkingUpdate) {
                            checkingUpdate = true
                            viewModel.checkUpdate { result ->
                                checkingUpdate = false
                                if (result is SettingsViewModel.UpdateCheckResult.Available) {
                                    pendingUpdate = result.info
                                }
                            }
                        }
                    },
                    showChevron = !checkingUpdate,
                    trailing = {
                        if (checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Tokens.IconSize.md),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
// 行组件（全部构建在共享 GroupedList 规范之上）
// =============================================================================

/**
 * 账号行：头像首字符 + 昵称 + 用户名，点击进入改密。
 * 用 [GroupedRow] 的 trailing 槽放头像？不，头像在左侧语义更强——
 * 这里直接复用 GroupedRow 的图标位放一个圆形首字符头像效果不自然，
 * 故单独排布，但仍遵守 GroupedRow 的间距与触控规范。
 */
@Composable
private fun AccountRow(
    displayName: String,
    username: String,
    onClick: () -> Unit,
) {
    GroupedRow(
        title = displayName,
        subtitle = if (username.isNotBlank()) "@$username" else "点击修改密码",
        onClick = onClick,
        icon = null,
        trailing = {
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.md)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.firstOrNull()?.toString() ?: "U",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

/**
 * 开关行：复用 [GroupedRow] 的图标/标题/副标题，trailing 放 Switch，无 chevron。
 */
@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GroupedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        showChevron = false,
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/**
 * 信息行：图标 + 标题 + 值（无箭头，不可点击）。
 */
@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
) {
    GroupedRow(
        title = title,
        subtitle = value,
        icon = icon,
        showChevron = false,
    )
}

/**
 * 外观 - 主题切换。标题行 + 共享 [SegmentedControl]（跟随系统 / 浅色 / 深色）。
 */
@Composable
private fun ThemeRow(
    themeMode: String,
    onThemeChanged: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.md)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(Tokens.Radius.sm),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DarkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
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
        SegmentedControl(
            selected = themeMode,
            onSelected = onThemeChanged,
            options = listOf(
                "system" to "跟随系统",
                "light" to "浅色",
                "dark" to "深色",
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 通知监听状态行（状态 + 跳转）。用 [GroupedRow] 的 trailing 放色点，不用 Switch。
 */
@Composable
private fun NotificationStatusRow(
    granted: Boolean,
    onNavigate: () -> Unit,
) {
    val dotColor = if (granted) MaterialTheme.semantic.success else MaterialTheme.semantic.danger
    GroupedRow(
        title = "通知监听",
        subtitle = if (granted) "已开启，自动记账运行中" else "未授权，请前往设置开启",
        icon = Icons.Default.Security,
        iconTint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.semantic.danger,
        onClick = onNavigate,
        trailing = {
            Box(
                modifier = Modifier
                    .size(Tokens.Spacing.sm)
                    .background(color = dotColor, shape = CircleShape),
            )
        },
    )
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
