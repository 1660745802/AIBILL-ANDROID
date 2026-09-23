package com.aibill.android.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.TransferColor

/**
 * 类型三选控件（支出/收入/转账）。**替代 ManualRecordScreen.TypeSelector**。
 *
 * 视觉：
 * - 选中态：着色背景 + 加粗文字（支出红、收入绿、转账主色）
 * - 未选中：surfaceContainerHigh 中性底
 */
@Composable
fun TypeSegmentedControl(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Pair<String, String>> = listOf(
        "expense" to "支出",
        "income" to "收入",
        "transfer" to "转账",
    ),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg = when {
                !isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
                value == "expense" -> ExpenseColor.copy(alpha = 0.12f)
                value == "income" -> IncomeColor.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val fg = when {
                !isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                value == "expense" -> ExpenseColor
                value == "income" -> IncomeColor
                else -> TransferColor
            }
            Surface(
                shape = RoundedCornerShape(Tokens.Radius.md),
                color = bg,
                modifier = Modifier
                    .weight(1f)
                    .height(Tokens.TouchTarget.normal)
                    .clickable { onSelected(value) },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = fg,
                    )
                }
            }
        }
    }
}
