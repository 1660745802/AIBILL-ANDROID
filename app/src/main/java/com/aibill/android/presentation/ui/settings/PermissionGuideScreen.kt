package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.SecondaryButton
import com.aibill.android.util.BatteryOptimizationHelper

/**
 * 权限引导页面
 * 当用户开启"自动记账"开关时引导开启所需权限
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isNotificationListenerEnabled by remember {
        mutableStateOf(BatteryOptimizationHelper.isNotificationListenerEnabled(context))
    }
    var isBatteryOptimized by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }
    var isPostNotificationEnabled by remember {
        mutableStateOf(BatteryOptimizationHelper.isPostNotificationEnabled(context))
    }

    // 返回时自动刷新权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationListenerEnabled =
                    BatteryOptimizationHelper.isNotificationListenerEnabled(context)
                isBatteryOptimized =
                    BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                isPostNotificationEnabled =
                    BatteryOptimizationHelper.isPostNotificationEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted = isNotificationListenerEnabled && isBatteryOptimized && isPostNotificationEnabled
    val brandGuideText = remember { BatteryOptimizationHelper.getBrandGuideText() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动记账权限引导") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 可滚动的权限列表
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "为保证自动记账功能正常运行，请开启以下权限：",
                    style = MaterialTheme.typography.bodyLarge
                )

            // 1. 通知监听权限
            PermissionItem(
                title = "通知监听权限",
                description = "读取支付通知以自动记账",
                isGranted = isNotificationListenerEnabled,
                buttonText = "去开启",
                onAction = {
                    BatteryOptimizationHelper.openNotificationListenerSettings(context)
                }
            )

            // 检测"权限有但服务未连接"（vivo等ROM首次授权需toggle）
            if (isNotificationListenerEnabled && !com.aibill.android.service.NotificationMonitorService.isConnected) {
                androidx.compose.material3.Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "⚠️ 通知监听未生效",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "请关闭再重新打开通知使用权即可恢复。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        com.aibill.android.presentation.theme.SecondaryButton(
                            text = "前往设置",
                            onClick = { BatteryOptimizationHelper.openNotificationListenerSettings(context) },
                        )
                    }
                }
            }

            // 2. 通知弹窗权限（Android 13+ 发通知需要）
            PermissionItem(
                title = "通知弹窗权限",
                description = "检测到支付时弹窗提醒确认",
                isGranted = isPostNotificationEnabled,
                buttonText = "去开启",
                onAction = {
                    BatteryOptimizationHelper.openAppNotificationSettings(context)
                }
            )

            // 3. 电池优化白名单
            PermissionItem(
                title = "电池优化白名单",
                description = "避免系统杀死后台服务",
                isGranted = isBatteryOptimized,
                buttonText = "去设置",
                onAction = {
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context)
                }
            )

            // 4. 后台自启动（品牌特定）— 无法自动检测
            PermissionItem(
                title = "后台自启动",
                description = brandGuideText,
                isGranted = null, // 无法检测状态
                buttonText = "去设置",
                showAlwaysAction = true,
                onAction = {
                    val intent = BatteryOptimizationHelper.getManufacturerSettingsIntent(context)
                    if (intent != null) {
                        runCatching { context.startActivity(intent) }
                    }
                }
            )

            // 5. 无障碍服务（支付页面识别）— 无法程序化检测状态
            PermissionItem(
                title = "无障碍服务(支付页识别)",
                description = "识别微信/支付宝支付结果页，覆盖无通知场景",
                isGranted = null, // null 表示无法确认，不显示勾/叉
                buttonText = "去设置",
                showAlwaysAction = true,
                onAction = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            )

            // 6. 锁定最近任务（通用，防止被一键清理）
            PermissionItem(
                title = "锁定最近任务",
                description = "打开最近任务 → 找到 AIBILL → 下拉锁定（出现🔒图标）",
                isGranted = null,
                buttonText = "已了解",
                showAlwaysAction = true,
                onAction = { /* 无法程序化打开最近任务面板 */ }
            )
            }

            // 底部按钮（固定不随滚动）
            PrimaryButton(
                text = if (allGranted) "已全部开启" else "继续",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean?,
    buttonText: String,
    showAlwaysAction: Boolean = false,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // null = 无法检测，不显示图标
            when (isGranted) {
                true -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                false -> Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                null -> Spacer(modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isGranted != true || showAlwaysAction) {
                Spacer(modifier = Modifier.width(8.dp))
                SecondaryButton(text = buttonText, onClick = onAction)
            }
        }
    }
}
