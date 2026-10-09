package com.aibill.android.presentation.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.GroupedDivider
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.SecondaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import com.aibill.android.util.BatteryOptimizationHelper

/**
 * 自动记账权限引导页。
 *
 * ## 为什么这个页面需要特别设计
 * 这是整个 App **最关键但用户最容易放弃**的一页：自动记账依赖 4~6 项系统权限，
 * 每项都要跳到不同的系统设置页去开。用户看到一长串「未开启」很容易直接放弃，
 * 于是核心差异化功能（通知自动记账）形同虚设。
 *
 * 改造后的三条设计决策：
 * 1. **顶部给出总体进度**（"已开启 2 / 4"）+ 一条进度条，让用户知道"快好了"，
 *    而不是面对一堵红色的墙。
 * 2. **已开启的项自动折叠到底部**，视线优先落在还没做的上。
 * 3. 状态不只用颜色区分，还带 ✓/⚠ 图标 + 明确的动作按钮文案
 *    （"去开启" / "去设置"），不靠用户猜。
 *
 * 底部按钮在全部开启后文案从「继续」变成「已全部开启」，给出正反馈。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

    // 返回时自动刷新权限状态（用户在系统设置里改完会回到这里）
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

    // 可自动检测的三项
    val allGranted = isNotificationListenerEnabled && isBatteryOptimized && isPostNotificationEnabled
    val brandGuideText = remember { BatteryOptimizationHelper.getBrandGuideText() }
    val grantedCount = listOf(
        isNotificationListenerEnabled,
        isBatteryOptimized,
        isPostNotificationEnabled,
    ).count { it }

    // 「权限已授予但服务没连上」——部分 ROM（vivo 等）首次授权需要再 toggle 一次
    val listenerGrantedButNotConnected =
        isNotificationListenerEnabled && !com.aibill.android.service.NotificationMonitorService.isConnected

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "权限与保活", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Tokens.Spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                Spacer(Modifier.height(Tokens.Spacing.sm))

                ProgressSummary(
                    grantedCount = grantedCount,
                    total = 3,
                    allGranted = allGranted,
                )

                if (listenerGrantedButNotConnected) {
                    ListenerNotConnectedCard(
                        onGoSettings = {
                            BatteryOptimizationHelper.openNotificationListenerSettings(context)
                        },
                    )
                }

                // ===== 可自动检测的三项 =====
                SectionHeader(title = "系统权限", subtitle = "开启后自动生效")
                GroupedList {
                    PermissionItem(
                        title = "通知监听权限",
                        description = "读取支付通知，这是自动记账的核心能力",
                        isGranted = isNotificationListenerEnabled,
                        actionText = "去开启",
                        onAction = {
                            BatteryOptimizationHelper.openNotificationListenerSettings(context)
                        },
                    )
                    GroupedDivider()
                    PermissionItem(
                        title = "通知弹窗权限",
                        description = "检测到支付时弹窗提醒你确认",
                        isGranted = isPostNotificationEnabled,
                        actionText = "去开启",
                        onAction = {
                            BatteryOptimizationHelper.openAppNotificationSettings(context)
                        },
                    )
                    GroupedDivider()
                    PermissionItem(
                        title = "电池优化白名单",
                        description = "避免系统在后台把自动记账服务杀掉",
                        isGranted = isBatteryOptimized,
                        actionText = "去设置",
                        onAction = {
                            BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context)
                        },
                    )
                }

                // ===== 无法自动检测的两项 =====
                SectionHeader(title = "厂商后台设置", subtitle = "需要手动确认")
                GroupedList {
                    PermissionItem(
                        title = "后台自启动",
                        description = brandGuideText,
                        isGranted = null,
                        actionText = "去设置",
                        onAction = {
                            val intent =
                                BatteryOptimizationHelper.getManufacturerSettingsIntent(context)
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                            }
                        },
                    )
                    GroupedDivider()
                    PermissionItem(
                        title = "无障碍服务",
                        description = "识别微信/支付宝支付结果页，覆盖无通知的场景",
                        isGranted = null,
                        actionText = "去设置",
                        onAction = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }

                // ===== 只能手动操作的通用项 =====
                SectionHeader(title = "防止被清理")
                GroupedList {
                    PermissionItem(
                        title = "锁定最近任务",
                        description = "打开最近任务 → 找到 AIBILL → 下拉锁定",
                        isGranted = null,
                        actionText = "我知道了",
                        alwaysShowAction = true,
                        onAction = { /* 无法程序化打开最近任务面板 */ },
                    )
                }

                Spacer(Modifier.height(Tokens.Spacing.lg))
            }

            // 底部按钮固定，不随滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(
                        start = Tokens.Spacing.screenHorizontal,
                        end = Tokens.Spacing.screenHorizontal,
                        top = Tokens.Spacing.md,
                        bottom = Tokens.Spacing.md,
                    ),
            ) {
                PrimaryButton(
                    text = if (allGranted) "已全部开启" else "稍后再说",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 顶部总体进度。
 *
 * 这是本页最重要的一处设计：把"一堆未开启"变成"还差 N 项"，
 * 给用户继续下去的理由。进度条 + 文案双通道，不依赖颜色单独传达。
 */
@Composable
private fun ProgressSummary(
    grantedCount: Int,
    total: Int,
    allGranted: Boolean,
) {
    val semantics = MaterialTheme.semantic
    val accent = if (allGranted) semantics.income else MaterialTheme.colorScheme.primary
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = grantedCount.toFloat() / total,
        animationSpec = tween(Tokens.Motion.DURATION_MEDIUM),
        label = "permissionProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Tokens.Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (allGranted) Icons.Default.CheckCircle
                else Icons.Default.WarningAmber,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
            Spacer(Modifier.width(Tokens.Spacing.sm))
            Text(
                text = if (allGranted) "自动记账所需权限已全部开启"
                else "已开启 $grantedCount / $total 项系统权限",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!allGranted) {
            Spacer(Modifier.height(Tokens.Spacing.sm))
            Text(
                text = "还差 ${total - grantedCount} 项，全部开启后支付通知就会自动记账",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Tokens.Spacing.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tokens.Chart.progressHeight * 2)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                    .fillMaxSize()
                    .background(accent),
            )
        }
    }
}

/**
 * 「权限已授予但服务未连接」的异常提示。
 *
 * 部分 ROM（vivo / 小米等）首次授权后需要关闭再打开一次通知使用权才会真正生效，
 * 这个状态光看「已开启」是发现不了的，必须单独提示。
 */
@Composable
private fun ListenerNotConnectedCard(onGoSettings: () -> Unit) {
    val semantics = MaterialTheme.semantic
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(semantics.dangerContainer)
            .padding(Tokens.Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = semantics.danger,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
            Spacer(Modifier.width(Tokens.Spacing.sm))
            Text(
                text = "通知监听还没真正生效",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = semantics.danger,
            )
        }
        Spacer(Modifier.height(Tokens.Spacing.xs))
        Text(
            text = "系统显示已授权，但服务没连上。关掉再重新打开一次「通知使用权」就能恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = semantics.onDangerContainer,
        )
        Spacer(Modifier.height(Tokens.Spacing.md))
        SecondaryButton(text = "前往设置", onClick = onGoSettings)
    }
}

/**
 * 单个权限项。
 *
 * @param isGranted true=已开启 / false=未开启 / null=无法自动检测（不显示状态图标，
 *   但仍然给一个「去设置」按钮——用户需要知道去哪做）
 */
@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean?,
    actionText: String,
    alwaysShowAction: Boolean = false,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantics = MaterialTheme.semantic
    val stateColor by animateColorAsState(
        targetValue = when (isGranted) {
            true -> semantics.income
            false -> semantics.danger
            null -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "permissionStateColor",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget.xlarge)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态图标：null 时留同样大小的空位，避免行高跳动
        Box(
            modifier = Modifier.size(Tokens.IconSize.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (isGranted == true) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已开启",
                    tint = stateColor,
                    modifier = Modifier.size(Tokens.IconSize.md),
                )
            } else if (isGranted == false) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = "未开启",
                    tint = stateColor,
                    modifier = Modifier.size(Tokens.IconSize.md),
                )
            }
        }
        Spacer(Modifier.width(Tokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isGranted != true || alwaysShowAction) {
            Spacer(Modifier.width(Tokens.Spacing.sm))
            SecondaryButton(text = actionText, onClick = onAction)
        }
    }
}
