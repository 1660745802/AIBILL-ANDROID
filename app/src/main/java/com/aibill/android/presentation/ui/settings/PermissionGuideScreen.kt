package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
                isGranted = true, // 无法检测，默认信任用户已设置
                buttonText = "去设置",
                showAlwaysAction = true,
                onAction = {
                    val intent = BatteryOptimizationHelper.getManufacturerSettingsIntent(context)
                    if (intent != null) {
                        runCatching { context.startActivity(intent) }
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底部按钮
            PrimaryButton(
                text = if (allGranted) "已全部开启" else "请完成上述设置",
                onClick = onBack,
                enabled = allGranted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    showAlwaysAction: Boolean = false,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
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
            if (!isGranted || showAlwaysAction) {
                Spacer(modifier = Modifier.width(8.dp))
                SecondaryButton(text = buttonText, onClick = onAction)
            }
        }
    }
}
