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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.presentation.theme.AppTextButton
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: com.aibill.android.domain.repository.AuthRepository,
    userPreferences: UserPreferences,
) : ViewModel() {
    // PR #51：头像读 nickname/username 实际值（之前写死"用户"）
    val displayName: StateFlow<String> = userPreferences.nickname
        .combine(userPreferences.username) { nickname, username ->
            nickname?.takeIf { it.isNotBlank() }
                ?: username?.takeIf { it.isNotBlank() }
                ?: "用户"
        }
        .stateIn(viewModelScope, Eagerly, "用户")

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}

private val GradientStart = Color(0xFF00897B)
private val GradientEnd = Color(0xFF4DB6AC)

@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAiChat: () -> Unit = {},
    onNavigateToBudget: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToNotification: () -> Unit = {},
    onNavigateToCategoryManage: () -> Unit = {},
    onNavigateToAccountManage: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    onNavigateToTemplate: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("退出后需重新登录才能继续记账，本地未同步的数据不会丢失。确定退出吗？") },
            confirmButton = {
                AppTextButton(
                    text = "退出",
                    isDestructive = true,
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout(onLogout)
                    },
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { showLogoutConfirm = false })
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { UserHeaderCard(displayName = viewModel.displayName.collectAsState().value) }
        item { SectionLabel("功能") }
        item {
            MenuCard {
                ProfileMenuItem(
                    icon = Icons.Default.Chat, title = "AI 对话",
                    subtitle = "和 AI 聊聊你的消费习惯",
                    onClick = onNavigateToAiChat,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Savings, title = "预算管理",
                    subtitle = "设置月度预算，控制开支",
                    onClick = onNavigateToBudget,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Download, title = "数据导出",
                    subtitle = "导出 CSV 格式账单",
                    onClick = onNavigateToExport,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.AutoMirrored.Filled.List, title = "记账模板",
                    subtitle = "保存常用记账，一键复用",
                    onClick = onNavigateToTemplate,
                )
            }
        }
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
                    icon = Icons.Default.Notifications, title = "通知设置",
                    subtitle = "通知监听、弹窗、后台保活",
                    onClick = onNavigateToNotification,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Settings, title = "通用设置",
                    subtitle = "主题外观、深色模式、隐私安全",
                    onClick = onNavigateToSettings,
                )
            }
        }
        item { SectionLabel("其他") }
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
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun UserHeaderCard(displayName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(GradientStart, GradientEnd),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(24.dp),
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(displayName, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text("AIBILL · 智能记账", style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) { Column(content = content) }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector, title: String, subtitle: String? = null,
    iconBg: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit = {},
) {
    ListItem(
        headlineContent = {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        },
        supportingContent = if (subtitle != null) {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(10.dp), color = iconBg,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, contentDescription = null, tint = tint,
                        modifier = Modifier.size(20.dp))
                }
            }
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp))
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
