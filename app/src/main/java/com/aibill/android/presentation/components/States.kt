package com.aibill.android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一空状态组件。所有"暂无数据"展示都用这个。
 *
 * 视觉规范：
 * - 大图标（48dp，弱化颜色）
 * - 主标题（bodyLarge，onSurfaceVariant）
 * - 副标题（bodySmall，outline）
 */
@Composable
fun EmptyState(
    icon: ImageVector? = null,
    emoji: String? = null,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Tokens.TouchTarget.normal),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            } else if (emoji != null) {
                Text(text = emoji, style = MaterialTheme.typography.displayMedium)
            }
            Spacer(Modifier.height(Tokens.Spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Tokens.Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 搜索无结果空状态。
 */
@Composable
fun SearchEmptyState(keyword: String, modifier: Modifier = Modifier) {
    EmptyState(
        emoji = "🔍",
        title = "没有找到「$keyword」相关的记录",
        subtitle = "试试换个关键词搜索",
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
        )
    }
}

/**
 * 列表底部 append 失败的重试按钮。
 */
@Composable
fun AppendError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onRetry) {
            Text("加载更多失败，点击重试")
        }
    }
}
