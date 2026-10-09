package com.aibill.android.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一 Chip 系统。
 *
 * 改造前的问题：流水筛选区同时存在 M3 `FilterChip`（高 32dp、圆角 8dp）和
 * 手写 `Row(background=RoundedCornerShape(16.dp))`（高 ~34dp、圆角 16dp），
 * 两者并排时高度和圆角肉眼可见地对不齐。
 *
 * 现在全部走本文件：**高度恒定 32dp、圆角恒定 pill、横向内边距 12dp**。
 */

/**
 * 可选中的筛选胶囊。
 *
 * 选中态用「主色实底 + 打勾图标」而不是仅靠换色——换色在色弱用户眼里
 * 区分度不够，图标是第二重编码。
 *
 * @param selected 是否选中
 * @param onClick 点击回调
 * @param label 文本
 * @param accent 自定义强调色（如支出红 / 收入绿）。null 表示用主题主色。
 * @param leadingIcon 未选中时显示的图标（选中时自动换成对勾）
 */
@Composable
fun AppChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    val containerTarget = if (selected) accentColor else Color.Transparent
    val contentTarget = if (selected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderTarget = if (selected) accentColor else MaterialTheme.colorScheme.outlineVariant

    val container by animateColorAsState(
        targetValue = if (enabled) containerTarget else containerTarget.copy(alpha = 0.3f),
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = contentTarget,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "chipContent",
    )
    val border by animateColorAsState(
        targetValue = borderTarget,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "chipBorder",
    )

    Row(
        modifier = modifier
            .height(Tokens.Chip.height)
            .clip(CircleShape)
            .background(container)
            .border(BorderStroke(Tokens.Border.thin, border), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Tokens.Chip.paddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        val icon = when {
            selected -> Icons.Default.Check
            leadingIcon != null -> leadingIcon
            else -> null
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(Tokens.IconSize.xs),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 不可移除的信息胶囊（只读标签）。
 */
@Composable
fun AppInfoChip(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    leadingIcon: ImageVector? = null,
) {
    val fg = accent ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .height(Tokens.Chip.height)
            .clip(CircleShape)
            .background(
                if (accent != null) fg.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .padding(horizontal = Tokens.Chip.paddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(Tokens.IconSize.xs),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent != null) fg else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 可移除的筛选胶囊（点一下即取消该筛选）。
 *
 * 与 [AppChip] 的区别：视觉上是"已生效的条件"，点整颗即删除，
 * 不需要再点一次去切换选中态。
 */
@Composable
fun RemovableFilterChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val fg = accent ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(Tokens.Chip.height)
            .clip(CircleShape)
            .background(fg.copy(alpha = 0.12f))
            .clickable(onClick = onRemove)
            .padding(start = Tokens.Chip.paddingHorizontal, end = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "移除筛选：$label",
            tint = fg,
            modifier = Modifier.size(Tokens.IconSize.xs),
        )
    }
}

/**
 * 横向滚动的 Chip 行。两侧留出 20dp 页面边距，滚动到末尾时最后一项
 * 不会被裁掉（contentPadding）。
 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Tokens.Spacing.screenHorizontal,
    verticalPadding: Dp = Tokens.Spacing.sm,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 自动换行的 Chip 组（用于分类 / 标签等多选）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(
    modifier: Modifier = Modifier,
    horizontalGap: Dp = Tokens.Spacing.sm,
    verticalGap: Dp = Tokens.Spacing.sm,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(horizontalGap),
        verticalArrangement = Arrangement.spacedBy(verticalGap),
    ) {
        content()
    }
}

/**
 * 带计数徽标的主色实心按钮型 chip，用于「筛选 ·2」。
 * 计数 > 0 时背景变主色，让"当前有筛选生效"这件事在余光里也能被看到。
 */
@Composable
fun FilterTriggerChip(
    label: String,
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val active = activeCount > 0
    val bg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "filterTriggerBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "filterTriggerFg",
    )
    Row(
        modifier = modifier
            .height(Tokens.Chip.height)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Chip.paddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(Tokens.IconSize.xs),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
        if (active) {
            Box(
                modifier = Modifier
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(fg.copy(alpha = 0.22f))
                    .padding(horizontal = Tokens.Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$activeCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                )
            }
        }
    }
}
