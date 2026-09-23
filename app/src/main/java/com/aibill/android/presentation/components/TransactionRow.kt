package com.aibill.android.presentation.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一的流水条目。**替代 Home + Transactions 两份旧实现**。
 *
 * 视觉规范：
 * - 圆形 emoji 头像（44dp，surfaceContainerHigh 底）
 * - 中部：分类名（bodyLarge） + 描述（bodySmall）
 * - 右侧：金额（按 type 着色）+ 时间
 * - 来源标记：APP_NOTIFICATION 时显示「⚡自动」
 *
 * @param onClick 点击回调（如跳详情）。为 null 时不消费点击
 * @param highlight 标记该条目（如待同步），轻微高亮背景
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
) {
    val cardModifier = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(Tokens.Radius.lg),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation.low),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.listItemVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryAvatar(
                icon = transaction.categoryIcon,
                modifier = Modifier.size(Tokens.Avatar.lg),
            )
            Spacer(Modifier.width(Tokens.Spacing.md + Tokens.Spacing.xs))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.categoryName ?: "未分类",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!transaction.description.isNullOrBlank()) {
                    Text(
                        text = transaction.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    transaction.time?.let { time ->
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (transaction.source == TransactionSource.APP_NOTIFICATION) {
                        if (transaction.time != null) {
                            Spacer(Modifier.width(Tokens.Spacing.xs + Tokens.Spacing.xs))
                        }
                        Text(
                            text = "⚡自动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            AmountText(
                amount = transaction.amount,
                type = transaction.type,
                style = MaterialTheme.typography.titleMedium,
                showSign = true,
            )
        }
    }
}

/**
 * 分类头像：圆形 emoji 徽章。可复用于任何"分类图标"场景。
 */
@Composable
fun CategoryAvatar(
    icon: String?,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    iconSize: Int = 22,
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
