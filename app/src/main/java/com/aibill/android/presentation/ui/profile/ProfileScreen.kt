package com.aibill.android.presentation.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.ConfirmDialog
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.theme.BrandGradientSubtle
import com.aibill.android.presentation.theme.Tokens

/**
 * Profile 页所有导航回调（合并到一个参数，避免 LongParameterList）。
 */
data class ProfileScreenNavigation(
    val onSettings: () -> Unit = {},
    val onNotificationCenter: () -> Unit = {},
    val onPermissionGuide: () -> Unit = {},
    val onCategoryManage: () -> Unit = {},
    val onAccountManage: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onLogout: () -> Unit = {},
)

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    navigation: ProfileScreenNavigation = ProfileScreenNavigation(),
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val totalTransactions by viewModel.totalTransactions.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录",
            message = "退出后需重新登录才能继续记账，本地未同步的数据不会丢失。确定退出吗？",
            confirmText = "退出",
            isDestructive = true,
            onConfirm = {
                showLogoutConfirm = false
                viewModel.logout(navigation.onLogout)
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "我的") {
                IconButton(onClick = navigation.onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = Tokens.Spacing.screenHorizontal,
                end = Tokens.Spacing.screenHorizontal,
                top = Tokens.Spacing.xl,
                bottom = Tokens.Spacing.huge,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
        ) {
            item(key = "hero") {
                UserHeroCard(
                    displayName = displayName,
                    username = username,
                    totalTransactions = totalTransactions,
                )
            }

            item(key = "section_content") {
                SectionLabel("我的内容")
            }
            item(key = "content_card") {
                MenuCard {
                    ProfileMenuItem(
                        icon = Icons.Default.Category,
                        title = "分类管理",
                        subtitle = "管理收支分类",
                        onClick = navigation.onCategoryManage,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.AccountBalance,
                        title = "账户管理",
                        subtitle = "管理钱包和银行卡",
                        onClick = navigation.onAccountManage,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.Delete,
                        title = "回收站",
                        subtitle = "已删除的记录",
                        badge = trashCount.takeIf { it > 0 },
                        onClick = navigation.onTrash,
                    )
                }
            }

            item(key = "section_automation") {
                SectionLabel("自动化")
            }
            item(key = "automation_card") {
                MenuCard {
                    ProfileMenuItem(
                        icon = Icons.Default.Notifications,
                        title = "通知中心",
                        subtitle = "查看待确认的自动记账",
                        onClick = navigation.onNotificationCenter,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.Shield,
                        title = "权限与保活",
                        subtitle = "通知监听、自启动、电池优化",
                        onClick = navigation.onPermissionGuide,
                    )
                }
            }

            item(key = "danger_card") {
                Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                DangerCard(
                    title = "退出登录",
                    onClick = { showLogoutConfirm = true },
                )
            }
        }
    }
}

// =============================================================================
// 组件
// =============================================================================

/**
 * 用户 Hero 卡（主题色渐变）。
 *
 * 设计要点：
 * - 头像用首字符圆形（避免 emoji 跨设备不一致）
 * - 副标题展示用户名（@username）
 * - 底部单指标：累计笔数（**不展示金额**，避免和首页/统计冲突）
 */
@Composable
private fun UserHeroCard(
    displayName: String,
    username: String,
    totalTransactions: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        // 柔和品牌渐变背景下的文字色：
        // - Light mode：浅 teal 渐变 + 深 teal 文字 (onPrimaryContainer)
        // - Dark mode：接受以 teal 系色文本表达（柔和优先）
        val heroTextColor = MaterialTheme.colorScheme.onPrimaryContainer
        val heroTextSecondary = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        val heroAvatarBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        val heroAvatarText = MaterialTheme.colorScheme.onPrimaryContainer
        val heroIconTint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = BrandGradientSubtle, shape = RoundedCornerShape(Tokens.Radius.xl))
                .padding(horizontal = Tokens.Spacing.xxl, vertical = Tokens.Spacing.xl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像：主色 container + 深 teal 文字（柔和统一）
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(color = heroAvatarBg, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName.firstOrNull()?.toString() ?: "U",
                        color = heroAvatarText,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = heroTextColor,
                    )
                    if (username.isNotBlank()) {
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodySmall,
                            color = heroTextSecondary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Tokens.Spacing.xl))

            // 成就指标：单列“累计笔数”（零金额）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatCell(
                    icon = Icons.Default.Category,
                    label = "累计笔数",
                    value = "$totalTransactions 笔",
                    iconTint = heroIconTint,
                    labelColor = heroTextSecondary,
                    valueColor = heroTextColor,
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.size(Tokens.Spacing.sm))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = Tokens.Spacing.xs),
    )
}

@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.low),
    ) {
        Column(content = content)
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp, end = Tokens.Spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = Tokens.Border.thin,
    )
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconBg: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    tint: Color = MaterialTheme.colorScheme.primary,
    badge: Int? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧图标圆角方块
        Box(
            modifier = Modifier
                .size(Tokens.Avatar.md)
                .background(color = iconBg, shape = RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
        // 中间标题+副标题
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 右侧：Badge 或 Chevron
        if (badge != null) {
            BadgePill(count = badge)
            Spacer(modifier = Modifier.size(Tokens.Spacing.sm))
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(Tokens.IconSize.sm),
        )
    }
}

/**
 * 数字徽章（低调灰底 pill）。仅在 count > 0 时显示。
 */
@Composable
private fun BadgePill(count: Int) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Tokens.Radius.pill),
            )
            .padding(horizontal = Tokens.Spacing.sm, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 危险操作项。低饱和 errorContainer 背景，柔和但仍可识别。
 */
@Composable
private fun DangerCard(title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
            Spacer(modifier = Modifier.size(Tokens.Spacing.lg))
            Text(
                title,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    AiBillTheme {
        ProfileScreen()
    }
}
