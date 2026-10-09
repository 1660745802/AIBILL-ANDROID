package com.aibill.android.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.onAccentFor
import com.aibill.android.presentation.theme.semantic

/**
 * 类型三选控件（支出 / 收入 / 转账）。
 *
 * ## 视觉规范（改造要点）
 * 旧版三段各自独立着色，选中时只有背景色变化，在深色模式下三种选中态
 * 挤在一起容易误读。新版改为**「轨道 + 滑动指示块」**：
 * - 整行是一个 pill 轨道（surfaceContainerHighest）
 * - 选中项有一块实底指示块，颜色随业务类型（支出红 / 收入绿 / 转账主色）
 * - 指示块宽度按选中位置平滑滑动，而不是瞬间跳过去
 * - 文字在选中态加粗 + 变白，未选中保持中性灰
 *
 * 这样「现在选的是哪个」由**位置 + 底色 + 字重**三重编码，盲操作也能确认。
 */
@Composable
fun TypeSegmentedControl(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Pair<String, String>> = defaultTypeOptions,
    horizontalPadding: androidx.compose.ui.unit.Dp = Tokens.Spacing.screenHorizontal,
) {
    val semantics = MaterialTheme.semantic
    val accent = when (selected) {
        "income" -> semantics.income
        "transfer" -> semantics.transfer
        else -> semantics.expense
    }
    // 成对前景色：深色主题下 accent 是亮色，硬编码白字会看不见
    val onAccent = MaterialTheme.onAccentFor(selected)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = Tokens.Spacing.xs)
            .height(Tokens.TouchTarget.normal)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(Tokens.Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val indicator by animateColorAsState(
                targetValue = if (isSelected) accent else Color.Transparent,
                animationSpec = tween(Tokens.Motion.DURATION_FAST),
                label = "typeSegmentBg",
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) onAccent
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(Tokens.Motion.DURATION_FAST),
                label = "typeSegmentFg",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(indicator)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onSelected(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** 默认三类型选项，供筛选区/编辑弹窗复用。 */
val defaultTypeOptions = listOf(
    "expense" to "支出",
    "income" to "收入",
    "transfer" to "转账",
)

/**
 * 通用分段控件（统计页「支出 / 收入」等）。
 *
 * 与 [TypeSegmentedControl] 的差别：这里只有中性色，不绑定业务语义色。
 */
@Composable
fun SegmentedControl(
    selected: String,
    onSelected: (String) -> Unit,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(Tokens.TouchTarget.small)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(Tokens.Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxs),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                animationSpec = tween(Tokens.Motion.DURATION_FAST),
                label = "segmentBg",
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(Tokens.Motion.DURATION_FAST),
                label = "segmentFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelected(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = fg,
                    // 两字标签（"支出"/"收入"）一旦被挤压就会折成两行。
                    // 父容器再紧也不许换行 —— 宁可被截断也不要破坏行高。
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = Tokens.Spacing.md),
                )
            }
        }
    }
}

/**
 * 记账页的分类选择网格项。
 *
 * 选中态用**主色实心圆 + 分类名变主色 + 圆放大 1.06**三重反馈：
 * 只靠换底色在深色模式和低亮度屏幕上不够明显。
 */
@Composable
fun CategoryPickerItem(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val circleColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "catCircle",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "catLabel",
    )
    val circleSize by animateDpAsState(
        targetValue = if (selected) 48.dp else 44.dp,
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "catCircleSize",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Tokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(Tokens.Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
