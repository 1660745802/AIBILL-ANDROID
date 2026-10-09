package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.components.AmountText
import com.aibill.android.presentation.components.CategoryAvatar
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 日期分组头。**重设计**：
 * - 作为 `stickyHeader` 使用，所以必须有不透明背景（滚动时压住下方内容）。
 * - 左侧日期（今天 / 昨天 / 10月15日 周三，由上游 group.date 决定），
 *   右侧当日支出/收入合计（语义色，等宽数字）。
 * - 底部一条 0.5dp 发丝线替代大面积留白，减少滚动时的视觉断裂。
 */
@Composable
internal fun DateHeader(
    date: String,
    transactions: List<Transaction>,
    modifier: Modifier = Modifier,
) {
    val semantics = MaterialTheme.semantic
    val expenseTotal = transactions
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { it.amount }
    val incomeTotal = transactions
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amount }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.List.dateHeaderHeight)
                .padding(
                    horizontal = Tokens.Spacing.screenHorizontal,
                    vertical = Tokens.Spacing.sm,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = date,
                style = AppTextStyles.SectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                if (expenseTotal > 0) {
                    AmountText(
                        amount = expenseTotal,
                        type = TransactionType.EXPENSE,
                        style = AmountTypography.Chip,
                        showSign = false,
                        color = semantics.expense,
                    )
                }
                if (incomeTotal > 0) {
                    AmountText(
                        amount = incomeTotal,
                        type = TransactionType.INCOME,
                        style = AmountTypography.Chip,
                        showSign = false,
                        color = semantics.income,
                    )
                }
            }
        }
    }
}

/**
 * 流水列表单行。**重设计**：
 * - 放弃手写 swipe-to-delete（误触成本高、纯红满铺刺眼）。
 *   改为 `combinedClickable`：单击进详情，长按弹出操作菜单（由上游处理）。
 * - 左侧共享 [CategoryAvatar]（42dp），中间分类名 + 描述，
 *   右侧共享 [AmountText]（`AmountTypography.Row` 等宽数字）+ 时间。
 * - 按下整行微缩 1.5%，给到点击的视觉确认。
 *
 * @param onClick 单击：进入详情
 * @param onLongClick 长按：弹出底部操作菜单
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransactionItem(
    transaction: Transaction,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "txItemPressScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(
                horizontal = Tokens.Spacing.screenHorizontal,
                vertical = Tokens.Spacing.listItemVertical,
            )
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            icon = transaction.categoryIcon,
            modifier = Modifier.size(Tokens.Avatar.lg),
        )

        Spacer(modifier = Modifier.width(Tokens.Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.categoryName ?: "未分类",
                style = AppTextStyles.ListTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!transaction.description.isNullOrBlank()) {
                Text(
                    text = transaction.description,
                    style = AppTextStyles.ListSubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(Tokens.Spacing.sm))

        Column(horizontalAlignment = Alignment.End) {
            AmountText(
                amount = transaction.amount,
                type = transaction.type,
                style = AmountTypography.Row,
                showSign = true,
            )
            transaction.time?.let { time ->
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
    }
}
