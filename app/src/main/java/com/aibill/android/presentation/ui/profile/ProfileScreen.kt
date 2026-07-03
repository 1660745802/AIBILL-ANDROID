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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
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
import com.aibill.android.domain.usecase.StreakInfo
import com.aibill.android.domain.usecase.StreakTracker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    streakTracker: StreakTracker,
    private val authRepository: com.aibill.android.domain.repository.AuthRepository,
) : ViewModel() {
    val streakInfo: StateFlow<StreakInfo> = streakTracker.streakInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo())

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}

private val GradientStart = Color(0xFF009688)
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
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val streakInfo by viewModel.streakInfo.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { UserHeaderCard(streakInfo = streakInfo) }
        item { SectionLabel("功能") }
        item {
            MenuCard {
                ProfileMenuItem(
                    icon = Icons.Default.Chat, title = "AI 对话",
                    subtitle = "和 AI 聊聊你的消费习惯",
                    iconBg = Color(0xFFE3F2FD), onClick = onNavigateToAiChat,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Savings, title = "预算管理",
                    subtitle = "设置月度预算，控制开支",
                    iconBg = Color(0xFFFFF3E0), onClick = onNavigateToBudget,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Download, title = "数据导出",
                    subtitle = "导出 CSV 格式账单",
                    iconBg = Color(0xFFE8F5E9), onClick = onNavigateToExport,
                )
            }
        }
        item { SectionLabel("设置") }
        item {
            MenuCard {
                ProfileMenuItem(
                    icon = Icons.Default.Category, title = "分类管理",
                    subtitle = "自定义收支分类",
                    iconBg = Color(0xFFF3E5F5), onClick = onNavigateToCategoryManage,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.AccountBalance, title = "账户管理",
                    subtitle = "管理你的钱包和银行卡",
                    iconBg = Color(0xFFE0F7FA), onClick = onNavigateToAccountManage,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Delete, title = "回收站",
                    subtitle = "查看和恢复已删除的记录",
                    iconBg = Color(0xFFFBE9E7), onClick = onNavigateToTrash,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Notifications, title = "通知设置",
                    subtitle = "自动记账和提醒",
                    iconBg = Color(0xFFFFF8E1), onClick = onNavigateToNotification,
                )
                MenuDivider()
                ProfileMenuItem(
                    icon = Icons.Default.Settings, title = "通用设置",
                    subtitle = "主题、语言等偏好",
                    iconBg = Color(0xFFECEFF1), onClick = onNavigateToSettings,
                )
            }
        }
        item { SectionLabel("其他") }
        item {
            MenuCard {
                ProfileMenuItem(
                    icon = Icons.AutoMirrored.Filled.Logout, title = "退出登录",
                    iconBg = Color(0xFFFFEBEE),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { viewModel.logout(onLogout) },
                )
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun UserHeaderCard(streakInfo: StreakInfo) {
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
            Text("用户", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Color.White)
            Text("AIBILL · 智能记账", style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StreakStat(streakInfo.currentStreak.toString(), "连续天数")
                StreakStat(streakInfo.longestStreak.toString(), "最长连续")
                StreakStat(streakInfo.totalCount.toString(), "总记账数")
            }
        }
    }
}

@Composable
private fun StreakStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f))
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
    iconBg: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(title, color = tint, fontWeight = FontWeight.Medium) },
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
