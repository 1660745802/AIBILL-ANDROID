package com.aibill.android.presentation.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.aibill.android.presentation.theme.BrandGradient
import com.aibill.android.presentation.theme.Tokens

@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToNotificationCenter: () -> Unit = {},
    onNavigateToPermissionGuide: () -> Unit = {},
    onNavigateToCategoryManage: () -> Unit = {},
    onNavigateToAccountManage: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录",
            message = "退出后需重新登录才能继续记账，本地未同步的数据不会丢失。确定退出吗？",
            confirmText = "退出",
            isDestructive = true,
            onConfirm = {
                showLogoutConfirm = false
                viewModel.logout(onLogout)
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "我的") },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(Tokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
        ) {
            item { UserHeaderCard(displayName = displayName) }
            item { SectionLabel("管理") }
            item {
                MenuCard {
                    ProfileMenuItem(
                        icon = Icons.Default.Category, title = "分类管理",
                        subtitle = "自定义收支分类",
                        onClick = onNavigateToCategoryManage,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.AccountBalance, title = "账户管理",
                        subtitle = "管理你的钱包和银行卡",
                        onClick = onNavigateToAccountManage,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.Delete, title = "回收站",
                        subtitle = "查看和恢复已删除的记录",
                        onClick = onNavigateToTrash,
                    )
                }
            }
            item { SectionLabel("设置") }
            item {
                MenuCard {
                    ProfileMenuItem(
                        icon = Icons.Default.Notifications, title = "通知中心",
                        subtitle = "查看待确认的自动记账",
                        onClick = onNavigateToNotificationCenter,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.Shield, title = "权限与保活",
                        subtitle = "通知监听、电池优化、自启动",
                        onClick = onNavigateToPermissionGuide,
                    )
                    MenuDivider()
                    ProfileMenuItem(
                        icon = Icons.Default.Settings, title = "通用设置",
                        subtitle = "主题、隐私、服务器",
                        onClick = onNavigateToSettings,
                    )
                }
            }
            item {
                MenuCard {
                    ProfileMenuItem(
                        icon = Icons.AutoMirrored.Filled.Logout, title = "退出登录",
                        tint = MaterialTheme.colorScheme.error,
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        onClick = { showLogoutConfirm = true },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(Tokens.Spacing.xxl)) }
        }
    }
}

@Composable
private fun UserHeaderCard(displayName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = BrandGradient, shape = RoundedCornerShape(Tokens.Radius.xl))
                .padding(Tokens.Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.2f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(text = "👤", fontSize = 32.sp)
                }
            }
            Spacer(modifier = Modifier.height(Tokens.Spacing.md))
            Text(
                displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "AIBILL · 智能记账",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
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
        shape = RoundedCornerShape(Tokens.Radius.xl),
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
    onClick: () -> Unit = {},
) {
    ListItem(
        headlineContent = {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        },
        supportingContent = if (subtitle != null) {
            {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconBg,
                modifier = Modifier.size(Tokens.Avatar.md),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun ProfileScreenPreview() {
    AiBillTheme {
        ProfileScreen()
    }
}
