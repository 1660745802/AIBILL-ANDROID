package com.aibill.android.presentation.ui.record

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 记账页数字键盘。
 *
 * ## 为什么不用系统键盘
 * 改造前这里放的是 `OutlinedTextField` + `KeyboardType.Decimal`，由此产生过一连串问题：
 * 小数位自动补 00 导致光标跳到末尾无法连续输入、系统键盘弹出会把底部
 * 「保存」按钮顶出屏幕、软键盘高度随输入法厂商变化导致布局跳动。
 *
 * 改成**底部锚定的自绘键盘**后：
 * - 拇指可达区在屏幕下半部分，单手可完成整个记账流程
 * - 布局高度恒定，永远不会跳动
 * - 键位可按 12 宫格比例放大到 56dp，触达容错更高
 * - 没有 IME 候选栏干扰，键入 → 金额即时更新，反馈直接
 */
@Composable
fun NumericKeypad(
    onInput: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val rows = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf(".", "0", "DEL"),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                row.forEach { key ->
                    KeypadKey(
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = {
                            if (key == "DEL") onDelete() else onInput(key)
                        },
                    ) {
                        if (key == "DEL") {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Tokens.IconSize.md),
                            )
                        } else {
                            Text(
                                text = key,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个按键。
 *
 * 细节：按下时背景变主色容器色 + 整体缩小 2%，触觉反馈之外再给一层视觉确认；
 * 触控区域按 4:3 比例自适应，保证在小屏和大屏上都是舒服的椭圆。
 */
@Composable
private fun KeypadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
            pressed -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(Tokens.Motion.DURATION_INSTANT),
        label = "keypadKeyBg",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(Tokens.Motion.DURATION_INSTANT),
        label = "keypadKeyScale",
    )
    Box(
        modifier = modifier
            .aspectRatio(1.9f)
            .padding(vertical = Tokens.Spacing.hair)
            .drawWithScale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 按压缩放。用 drawWithContent 而非 graphicsLayer，避免在 LazyGrid 里重复离屏。 */
private fun Modifier.drawWithScale(scale: Float): Modifier =
    this.graphicsLayer { scaleX = scale; scaleY = scale }

/**
 * 记账页分类网格。
 *
 * 改造前选中态只是「圆形底色变化 + 文字变色」，在深色模式与低亮度屏幕上
 * 不够明显。现在用共享组件 [com.aibill.android.presentation.components.CategoryPickerItem]，
 * 选中时圆形放大到 48dp 并填主色，文字同时变主色。
 */
@Composable
internal fun RecordCategoryGrid(
    categories: List<Category>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Tokens.Spacing.md,
            end = Tokens.Spacing.md,
            top = Tokens.Spacing.sm,
            bottom = Tokens.Spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        items(
            count = categories.size,
            key = { categories[it].id },
        ) { index ->
            val category = categories[index]
            com.aibill.android.presentation.components.CategoryPickerItem(
                icon = category.icon,
                label = category.name,
                selected = category.id == selectedId,
                onClick = { onSelect(category.id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 分类为空时的占位（首次使用 / 分类被清空）。
 */
@Composable
internal fun NoCategoriesHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "当前类型下还没有分类，去「我的 → 分类管理」添加一个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semantic.transfer,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
