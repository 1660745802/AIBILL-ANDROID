package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aibill.android.presentation.theme.SecondaryButton
import com.aibill.android.presentation.theme.Tokens
import java.time.LocalDate

/**
 * 月份切换器：< 本月/指定月 >
 * 点击中间按钮跳回本月。
 */
@Composable
fun MonthSelector(
    year: Int,
    month: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToCurrent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isCurrent = year == LocalDate.now().year && month == LocalDate.now().monthValue
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.size(Tokens.Avatar.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上个月",
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }
        SecondaryButton(
            text = if (isCurrent) "本月" else "${year}年${month}月",
            onClick = onJumpToCurrent,
            modifier = Modifier.padding(horizontal = Tokens.Spacing.md),
        )
        IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.size(Tokens.Avatar.md),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下个月",
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }
    }
}

/**
 * 统计页 Tab 切换：支出 / 收入
 */
@Composable
fun StatsTabRow(
    selectedTab: String,
    onTabChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedTab == "expense",
            onClick = { onTabChanged("expense") },
            label = { Text("支出") },
        )
        Spacer(modifier = Modifier.width(Tokens.Spacing.sm))
        FilterChip(
            selected = selectedTab == "income",
            onClick = { onTabChanged("income") },
            label = { Text("收入") },
        )
    }
}
