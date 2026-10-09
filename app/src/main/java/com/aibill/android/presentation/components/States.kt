package com.aibill.android.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 统一空状态组件。所有"暂无数据"展示都用这个。
 *
 * 视觉规范：
 * - 图标装在 72dp 的圆形浅色底里（比裸图标更有"被安排过"的秩序感，
 *   也不会像 48dp 的 emoji 那样在不同机型上大小不一）
 * - 主标题 titleMedium（不能弱到像错误提示）
 * - 副标题 bodySmall，最多两行，居中，限宽 280dp 避免长句撑满屏幕
 * - 可选行动按钮：空状态是「邀请行动」，不是「告知没数据」
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Tokens.Spacing.xxxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            color = iconTint.copy(alpha = 0.10f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = iconTint.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(Tokens.Spacing.lg))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Tokens.Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(Tokens.Spacing.md))
                TextButton(onClick = onAction) {
                    Text(actionText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 搜索无结果空状态。
 */
@Composable
fun SearchEmptyState(
    keyword: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    EmptyState(
        title = "没有匹配「$keyword」的记录",
        subtitle = "换个关键词，或清除当前筛选条件再试",
        actionText = if (onClear != null) "清除筛选" else null,
        onAction = onClear,
        modifier = modifier,
    )
}

/**
 * 错误状态：说明发生了什么 + 给一条明确的下一步。
 * 不说「加载失败」这种无处可去的话。
 */
@Composable
fun ErrorState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionText: String = "重试",
    onAction: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        subtitle = subtitle,
        icon = icon,
        actionText = if (onAction != null) actionText else null,
        onAction = onAction,
        iconTint = MaterialTheme.semantic.danger,
        modifier = modifier,
    )
}

/**
 * 屏幕级 loading。
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Tokens.Avatar.md),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 列表底部 append loading。
 */
@Composable
fun AppendLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Tokens.IconSize.lg),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 列表底部 append 失败的重试。
 * 说明是哪一步失败 + 给出动作，而不是只丢一句「加载失败」。
 */
@Composable
fun AppendError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRetry) {
            Text(
                "没能加载更多记录，点击重试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
