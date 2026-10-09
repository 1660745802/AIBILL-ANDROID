package com.aibill.android.presentation.ui.statistics.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.components.SegmentedControl
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import java.time.LocalDate

/**
 * 统计页粘性头部：**同一行**容纳月份步进器与收支分段控件。
 *
 * 设计目标（替代原先上下堆两行的 MonthSelector + StatsTabRow）：
 * - 左侧 `‹ 2026年10月 ›`，中间文字可点 → 跳回本月（本月时显示「本月」）。
 * - 右侧收支切换用中性色 [SegmentedControl]（不绑语义色，避免头部先入为主地染红/绿）。
 * - 头部与页面同 `surface`，底部一条发丝线分隔，不再套一层卡片 / 阴影。
 */
@Composable
fun StatsHeader(
    year: Int,
    month: Int,
    selectedTab: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onJumpToCurrent: () -> Unit,
    onTabChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCurrent = year == LocalDate.now().year && month == LocalDate.now().monthValue

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Tokens.Spacing.screenHorizontal,
                    vertical = Tokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── 月份步进器 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    onClick = onPreviousMonth,
                )
                Text(
                    text = if (isCurrent) "本月" else "${year}年${month}月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onJumpToCurrent)
                        // 定宽：「本月」是 2 字、"2026年11月"是 8 字，宽度差很大。
                        // 不定宽的话，切月份会挤压右侧分段控件，导致「支出/收入」折行。
                        .width(MONTH_LABEL_WIDTH)
                        .padding(vertical = Tokens.Spacing.xs),
                )
                StepperIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    onClick = onNextMonth,
                )
            }

            // ── 收支分段控件（中性色）──
            SegmentedControl(
                // 定宽：外层是 SpaceBetween，不给定宽的话 weight(1f) 的分段宽度
                // 随可用空间浮动，月份文字一变就挤压 → 「支出/收入」折行
                modifier = Modifier.width(CONTROL_WIDTH),
                selected = selectedTab,
                onSelected = onTabChanged,
                options = IncomeExpenseOptions,
            )
        }
        HorizontalDivider(
            thickness = Tokens.Border.hairline,
            color = MaterialTheme.semantic.hairline,
        )
    }
}

private val IncomeExpenseOptions = listOf(
    "expense" to "支出",
    "income" to "收入",
)

/**
 * 圆形图标步进按钮，点击区固定 [Tokens.TouchTarget.normal] = 48dp，满足点击目标约束，
 * 内部可见圆盘用 [Tokens.Avatar.sm]。不使用 M3 IconButton（默认 40dp 容器）。
 */
@Composable
private fun StepperIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(Tokens.TouchTarget.normal)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Tokens.Avatar.sm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }
    }
}

/** 月份标签定宽：容纳 "2026年12月" 且切换时不挤压右侧控件。 */
private val MONTH_LABEL_WIDTH = 108.dp

/**
 * 收支分段控件定宽。两段各 66dp：标签 13sp 下「支出」约 26dp，
 * 加 12dp×2 内边距 = 50dp，留有余量，绝不折行。
 */
private val CONTROL_WIDTH = 132.dp
