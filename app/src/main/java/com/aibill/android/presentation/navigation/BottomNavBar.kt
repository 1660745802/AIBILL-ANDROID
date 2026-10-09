package com.aibill.android.presentation.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aibill.android.presentation.theme.Tokens

data class BottomBarNavigationItem(
    val label: String,
    val icon: ImageVector,
    val route: Route,
)

private val bottomNavItems = listOf(
    BottomBarNavigationItem("首页", Icons.Default.Home, Route.Home),
    BottomBarNavigationItem("流水", Icons.Default.ReceiptLong, Route.Transactions()),
    BottomBarNavigationItem("统计", Icons.Default.PieChart, Route.Statistics),
    BottomBarNavigationItem("我的", Icons.Default.AccountCircle, Route.Profile),
)

/**
 * 底部导航栏。
 *
 * ## 视觉规范（改造要点）
 * M3 默认 `NavigationBar` 选中态是「药丸底 + 主色图标」，图标与文字都很大，
 * 在 4 个 Tab 时显得笨重。这里改成更克制的方案：
 * - 顶边 1px 发丝线 + 半透明表面，把内容区和导航区分开但不切断
 * - 选中项：图标变主色 + **顶部 3dp 圆头指示条**，文字加粗
 * - 未选中项：图标与文字同为中性灰，透明度 0.7
 * - 高度 64dp（高于 M3 默认 80dp），给内容区多留 16dp
 * - 尊重系统手势区（navigationBarsPadding）
 *
 * 指示条是"当前位置"的强提示，比药丸底更轻，且不会让整个底栏变花。
 */
@Composable
fun BottomNavBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Tokens.Border.hairline)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = Tokens.Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                bottomNavItems.forEach { item ->
                    // 基于 destination.hierarchy 判高亮，对参数化 Route 也正确
                    val isSelected = currentDestination?.hierarchy?.any {
                        it.hasRoute(item.route::class)
                    } == true
                    BottomNavItem(
                        item = item,
                        selected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                navController.navigate(item.route) {
                                    popUpTo(Route.Home) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    item: BottomBarNavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "navItemTint",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "navItemIndicator",
    )
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Tokens.TouchTarget.normal)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
            )
            .padding(vertical = Tokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(18.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = indicatorAlpha)),
        )
        Spacer(Modifier.height(Tokens.Spacing.xs))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(Tokens.Radius.sm))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
        )
    }
}
