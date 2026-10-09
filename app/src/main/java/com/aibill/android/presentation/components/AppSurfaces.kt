package com.aibill.android.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 全局页面骨架组件。
 *
 * **为什么需要这一层**：改造前每个页面各自手写 `Card(shape=..., colors=...)`，
 * 结果是同一个 App 里出现了 16 / 18 / 20 / 24dp 四种卡片圆角和三种背景色，
 * "卡片"这个视觉单位在用户眼里是不一致的。这里把「卡、区块标题、分组列表、
 * 胶囊标签、统计格」五类容器统一定义，页面只做组合、不再自定义。
 */

// =============================================================================
// 卡片
// =============================================================================

/**
 * 标准页面卡片。
 *
 * - 背景 `surfaceContainerLow`（比页面 background 高一层，形成"浮起"但不用阴影）
 * - 圆角 lg（18dp）
 * - 零阴影：M3 的层级靠色调而非投影表达，投影只留给 FAB / Sheet / Dialog
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(Tokens.Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 大号汇总卡（首页月度卡 / 统计汇总卡 / 我的 Hero）。
 * 圆角 xl（24dp），内边距更大，是页面里唯一的"重量级"容器。
 */
@Composable
fun AppHeroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(Tokens.Spacing.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.xl)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(containerColor)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

// =============================================================================
// 区块标题
// =============================================================================

/**
 * 区块标题。
 *
 * 设计决定：左侧不加 emoji、不加竖色条、不加大写英文 eyebrow。
 * 只用「稍加字距的半粗文字 + 可选右侧操作」，保持安静，让内容自己说话。
 *
 * @param title 区块名，如 "今日流水"
 * @param subtitle 紧随标题的补充说明（弱化、单行截断）
 * @param action 右侧可点击操作（如"全部 ›"）
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.TouchTarget.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        ) {
            Text(
                text = title,
                style = AppTextStyles.SectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.size(Tokens.Spacing.sm))
            Row(
                modifier = Modifier.heightIn(min = Tokens.TouchTarget.small),
                verticalAlignment = Alignment.CenterVertically,
                content = action,
            )
        }
    }
}

// =============================================================================
// 分组列表（设置页 / 我的页的菜单）
// =============================================================================

/**
 * 分组列表容器：一块卡 + 内部若干行，行间有缩进对齐的细线。
 * 替代"每行一个独立 Card"的写法——后者会让长列表碎成一堆孤立的方块。
 */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = Tokens.Spacing.xs),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 分组列表内的分隔线。
 *
 * 缩进对齐规则：起点 = 列表内边距(16) + 图标容器宽(36) + 图标文字间距(16) = 68dp。
 * 线只出现在「文字区」，不会切到图标上，视觉上更干净。
 */
@Composable
fun GroupedDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 68.dp, end = Tokens.Spacing.lg),
        thickness = Tokens.Border.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

/**
 * 分组列表行：图标块 + 标题/副标题 + 右侧附加内容。
 * 按下时整行缩放 1.5%，给到触觉之外的视觉确认。
 *
 * @param leadingEmoji emoji 头像。分类 / 账户在本 App 里就是用 emoji 表示的
 *   （`Category.icon` / `Account.icon`），列表里必须能看到，否则用户无法
 *   快速区分「餐饮 / 交通 / 购物」。传了它会覆盖 [icon]。
 */
@Composable
fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingEmoji: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackground: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "groupedRowScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            // 只在真能点的时候才挂 clickable。
            // 否则会出现「有按压缩放反馈但点了没反应」的伪可点行
            //（设置页的「服务器地址」信息行就是这种）。
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick() },
                    )
                } else Modifier,
            )
            .heightIn(min = Tokens.TouchTarget.xlarge)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingEmoji != null) {
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.md)
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = leadingEmoji, fontSize = 18.sp)
            }
            Spacer(Modifier.size(Tokens.Spacing.lg))
        } else if (icon != null) {
            Box(
                modifier = Modifier
                    .size(Tokens.Avatar.md)
                    .clip(RoundedCornerShape(Tokens.Radius.sm))
                    .background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(Tokens.Spacing.lg))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppTextStyles.ListTitle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AppTextStyles.ListSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
            // 只有后面真的跟着 chevron 时才留间隙。原来无条件加 8dp，
            // 导致 showChevron=false 的行（分类/账户/回收站）图标整体偏左，
            // 与左侧头像的 16dp 边距不对称。
            if (showChevron && onClick != null) {
                Spacer(Modifier.size(Tokens.Spacing.sm))
            }
        }
        if (showChevron && onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(Tokens.IconSize.md),
            )
        }
    }
}

// =============================================================================
// 小部件
// =============================================================================

enum class PillTone { Neutral, Primary, Expense, Income, Warning, Danger }

/**
 * 胶囊标签。用于「自动」「待同步」「剩 22 天」这类状态标记。
 * 颜色由 [PillTone] 映射到语义色，明暗模式自动适配。
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val semantics = MaterialTheme.semantic
    val (bg, fg) = when (tone) {
        PillTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest to
            MaterialTheme.colorScheme.onSurfaceVariant
        PillTone.Primary -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.Expense -> semantics.expenseContainer to semantics.onExpenseContainer
        PillTone.Income -> semantics.incomeContainer to semantics.onIncomeContainer
        PillTone.Warning -> semantics.warningContainer to semantics.onWarningContainer
        PillTone.Danger -> semantics.dangerContainer to semantics.onDangerContainer
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Tokens.Spacing.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
        ) {
            if (leading != null) leading()
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(Tokens.IconSize.xs),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/**
 * 统计指标格：上 label / 下 value 纵向排列，可选左侧竖分隔线。
 *
 * 汇总卡底部的「日均 / 收入 / 结余」用这个，比一行 `label value` 文字更易扫读——
 * 每个指标有自己的基线，眼睛不需要在文字里找分隔。
 */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    showDividerBefore: Boolean = false,
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Row(
        modifier = modifier.heightIn(min = Tokens.TouchTarget.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDividerBefore) {
            Box(
                modifier = Modifier
                    .padding(end = Tokens.Spacing.md)
                    .width(Tokens.Border.hairline)
                    .height(26.dp)
                    .background(dividerColor.copy(alpha = 0.7f)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = valueStyle,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 尾部图标按钮（「更多」/「还原」等）。
 *
 * 为什么不用 `IconButton`：M3 的 IconButton 自带约 12dp 内边距，
 * 叠上列表行的 16dp 内边距后，图标实际离右边缘 28dp，
 * 而左侧头像离左边缘 16dp —— 两边不对称，看起来图标"偏左"。
 *
 * 这里把图标右对齐到内容边界（与左侧头像同为 16dp），
 * 同时保留 48dp 触控区，可达性不打折。
 */
@Composable
fun TrailingIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = Tokens.IconSize.sm,
) {
    Box(
        modifier = modifier
            .size(Tokens.TouchTarget.normal)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
