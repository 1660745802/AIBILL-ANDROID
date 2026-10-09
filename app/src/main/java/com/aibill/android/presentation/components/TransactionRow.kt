package com.aibill.android.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一的流水条目。首页与流水列表共用。
 *
 * ## 视觉规范
 * - 左侧 42dp 圆形 emoji 头像（surfaceContainerHighest 底）
 * - 中部：分类名（ListTitle） + 描述（ListSubtitle，弱化）
 * - 右侧：金额（等宽数字，按 type 着色）+ 时间（极弱）
 * - 自动记账来源用 [Pill] 标记，不再是裸文字 "⚡自动"
 * - 整行按下时向右微缩 2%，确认"点到了"
 *
 * @param onClick 点击回调。为 null 时不消费点击（用于纯展示场景）
 * @param highlight 高亮该行（如待同步），底色微染品牌色
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    showTime: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "txRowPressScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                else Color.Transparent,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier,
            )
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            icon = transaction.categoryIcon,
            modifier = Modifier.size(Tokens.Avatar.lg),
        )
        Spacer(Modifier.width(Tokens.Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.categoryName ?: "未分类",
                style = AppTextStyles.ListTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val hasSubLine = !transaction.description.isNullOrBlank()
            if (hasSubLine) {
                Text(
                    text = transaction.description,
                    style = AppTextStyles.ListSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showTime && (transaction.time != null || isAuto(transaction))) {
                Row(
                    modifier = Modifier.padding(top = Tokens.Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                ) {
                    transaction.time?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (isAuto(transaction)) {
                        Pill(
                            text = "自动",
                            tone = PillTone.Primary,
                            icon = Icons.Default.Bolt,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(Tokens.Spacing.sm))
        AmountText(
            amount = transaction.amount,
            type = transaction.type,
            style = AmountTypography.Row,
            showSign = true,
        )
    }
}

private fun isAuto(transaction: Transaction): Boolean =
    transaction.source == TransactionSource.APP_NOTIFICATION

/**
 * 分类头像：圆形 emoji 徽章。可复用于任何"分类图标"场景。
 *
 * @param accent 头像底色。null 时用中性 surface 层级，避免每行都跟品牌色抢注意力。
 */
@Composable
fun CategoryAvatar(
    icon: String?,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    iconSize: Int = 20,
) {
    Surface(
        shape = CircleShape,
        color = background,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = icon ?: "📝", fontSize = iconSize.sp)
        }
    }
}
