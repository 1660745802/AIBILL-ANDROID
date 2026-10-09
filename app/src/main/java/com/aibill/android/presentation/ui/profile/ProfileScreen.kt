package com.aibill.android.presentation.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AppHeroCard
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.ConfirmDialog
import com.aibill.android.presentation.components.GroupedDivider
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.GroupedRow
import com.aibill.android.presentation.components.Pill
import com.aibill.android.presentation.components.PillTone
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.components.StatCell
import com.aibill.android.presentation.theme.AiBillTheme
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

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

            // ============ 内容与数据 ============
            item(key = "section_content") {
                SectionHeader(title = "内容与数据")
            }
            item(key = "content_group") {
                GroupedList {
                    GroupedRow(
                        title = "分类管理",
                        subtitle = "管理收支分类",
                        icon = Icons.Default.Category,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onCategoryManage,
                    )
                    GroupedDivider()
                    GroupedRow(
                        title = "账户管理",
                        subtitle = "管理钱包和银行卡",
                        icon = Icons.Default.AccountBalanceWallet,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onAccountManage,
                    )
                    GroupedDivider()
                    GroupedRow(
                        title = "回收站",
                        subtitle = "已删除的记录",
                        icon = Icons.Default.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onTrash,
                        trailing = {
                            if (trashCount > 0) {
                                Pill(text = "$trashCount", tone = PillTone.Neutral)
                            }
                        },
                    )
                }
            }

            // ============ 自动化与安全 ============
            item(key = "section_automation") {
                SectionHeader(title = "自动化与安全")
            }
            item(key = "automation_group") {
                GroupedList {
                    GroupedRow(
                        title = "通知中心",
                        subtitle = "查看待确认的自动记账",
                        icon = Icons.Default.NotificationsActive,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onNotificationCenter,
                    )
                    GroupedDivider()
                    GroupedRow(
                        title = "权限与保活",
                        subtitle = "通知监听、自启动、电池优化",
                        icon = Icons.Default.Security,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onPermissionGuide,
                    )
                    GroupedDivider()
                    GroupedRow(
                        title = "设置",
                        subtitle = "外观、隐私、数据与关于",
                        icon = Icons.Default.Settings,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = navigation.onSettings,
                    )
                }
            }

            // ============ 退出登录（降级为普通一行） ============
            item(key = "logout_group") {
                Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
                GroupedList {
                    GroupedRow(
                        title = "退出登录",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = MaterialTheme.semantic.danger,
                        iconBackground = MaterialTheme.semantic.dangerContainer.copy(alpha = 0.4f),
                        titleColor = MaterialTheme.semantic.danger,
                        showChevron = false,
                        onClick = { showLogoutConfirm = true },
                    )
                }
            }
        }
    }
}

// =============================================================================
// 组件
// =============================================================================

/**
 * 用户 Hero 卡。
 *
 * 设计要点：
 * - 放弃写死的品牌渐变，改用 [AppHeroCard] 的 `surfaceContainerHigh` 纯色调分层，
 *   明暗模式自动成立（渐变版深色模式会糊）。
 * - 头像用首字符圆形（避免 emoji 跨设备不一致）。
 * - 底部用共享 [StatCell] 展示唯一成就指标：累计记账笔数。
 * - 右上角一个「已同步」小 [Pill]。
 */
@Composable
private fun UserHeroCard(
    displayName: String,
    username: String,
    totalTransactions: Int,
) {
    AppHeroCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像：主色 container + 深 teal 文字
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.hero)
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (username.isNotBlank()) {
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Pill(text = "已同步", tone = PillTone.Primary)
        }

        Spacer(modifier = Modifier.height(Tokens.Spacing.xl))

        // 唯一成就指标：累计记账笔数（等宽数字，不展示金额）
        StatCell(
            label = "累计记账",
            value = "$totalTransactions 笔",
            valueStyle = AmountTypography.Stat,
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ProfileScreenPreview() {
    AiBillTheme {
        ProfileScreen()
    }
}
