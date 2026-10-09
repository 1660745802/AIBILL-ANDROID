package com.aibill.android.presentation.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.components.AmountFormatter
import com.aibill.android.presentation.components.AmountHero
import com.aibill.android.presentation.components.AppHeroCard
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.LoadingState
import com.aibill.android.presentation.components.Pill
import com.aibill.android.presentation.components.PillTone
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.components.StatCell
import com.aibill.android.presentation.components.TransactionRow
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate

/**
 * 首页。
 *
 * ## 设计意图
 * 首页只回答两个问题：**这个月花了多少** 和 **今天花了什么**。
 * 页面因此被切成明确的两段：
 *
 * ```
 *  ┌─────────────────────────────────────┐
 *  │ AiBill                        🔔3  │  ← 品牌 + 全局待办
 *  ├─────────────────────────────────────┤
 *  │  ╭───────────────────────────────╮  │
 *  │  │ 本月支出      10月 · 剩22天 › │  │  ← Hero 卡（页面唯一重容器）
 *  │  │ ¥3,247.50                    │  │
 *  │  │ ▓▓▓░░░░░░░░░░░  已过 29%      │  │  ← 月度进度：时间维度
 *  │  │ ───┬──────┬──────┬───        │  │
 *  │  │ 日均  │ 收入 │ 结余           │  │
 *  │  ╰───────────────────────────────╯  │
 *  ├─────────────────────────────────────┤
 *  │ ⚠ 3 笔待同步          [立即同步]   │  ← 按需出现的横幅
 *  │                                     │
 *  │ 今日流水  支出 ¥xx    自动 3        │  ← SectionHeader
 *  │ ╭───────────────────────────────╮  │
 *  │ │ [🍜] 午餐    · 星巴克 ¥32      │  │  ← 整组一个容器，不是 N 张卡
 *  │ │ [🚕] 打车    · 滴滴   ¥18      │  │
 *  │ ╰───────────────────────────────╯  │
 *  └─────────────────────────────────────┘
 * ```
 *
 * 改造前的问题：TopBar 标题写成「2026 年 10 月」而不是页面标识；汇总卡右侧塞了
 * 「10月 · 剩22天」这种位置奇怪的文字；每条流水各自一张卡，页面碎成一堆方块。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToNotification: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToDetail: (Int) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            val message = when (event) {
                is HomeViewModel.UiEvent.ShowToast -> event.message
                is HomeViewModel.UiEvent.ShowError -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // 返回首页时刷新数据（编辑/记账后自动更新今日流水 + 月度）
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleResumeEffect(lifecycleOwner, lifecycleOwner) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // TopBar 只承担「我是谁 + 我有事要做」两件事：
            // 左侧品牌标识，右侧待确认通知（带角标）。月份等上下文交给 Hero 卡。
            AppTopBar(
                title = "AiBill",
                actions = {
                    IconButton(onClick = onNavigateToNotification) {
                        if (uiState.pendingNotificationCount > 0) {
                            BadgedBox(badge = {
                                Badge {
                                    Text("${uiState.pendingNotificationCount}")
                                }
                            }) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = "通知中心，${uiState.pendingNotificationCount} 条待确认",
                                )
                            }
                        } else {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "通知中心",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isLoading && uiState.todayTransactions.isEmpty() && !uiState.isRefreshing) {
                LoadingState()
            } else {
                HomeContent(
                    uiState = uiState,
                    onHeaderClick = onNavigateToStatistics,
                    onSyncClick = viewModel::triggerSync,
                    onItemClick = { id -> onNavigateToDetail(id) },
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeViewModel.HomeUiState,
    onHeaderClick: () -> Unit,
    onSyncClick: () -> Unit,
    onItemClick: (Int) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val autoCount = remember(uiState.todayTransactions) {
        uiState.todayTransactions.count { it.source == TransactionSource.APP_NOTIFICATION }
    }
    val todayExpense = remember(uiState.todayTransactions) {
        uiState.todayTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { it.amount }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Tokens.Spacing.screenHorizontal,
            end = Tokens.Spacing.screenHorizontal,
            top = Tokens.Spacing.sm,
            bottom = Tokens.Spacing.screenBottomWithFab,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
    ) {
        item(key = "summary") {
            MonthlySummaryCard(
                monthlyExpense = uiState.monthlyExpense,
                monthlyIncome = uiState.monthlyIncome,
                today = today,
                onClick = onHeaderClick,
            )
        }

        // 待同步横幅：只在真的有东西没同步时才占空间，且给得出明确动作
        item(key = "pending_sync") {
            AnimatedVisibility(
                visible = uiState.pendingSyncCount > 0,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PendingSyncBanner(
                    count = uiState.pendingSyncCount,
                    isSyncing = uiState.isSyncing,
                    onSyncClick = onSyncClick,
                )
            }
        }

        item(key = "today_title") {
            SectionHeader(
                title = "今日流水",
                subtitle = if (todayExpense > 0) {
                    "支出 ${AmountFormatter.toYuanDisplay(todayExpense)}"
                } else null,
                action = {
                    if (autoCount > 0) {
                        Pill(
                            text = "$autoCount 笔自动",
                            tone = PillTone.Primary,
                            icon = Icons.Default.CloudDone,
                        )
                    }
                },
            )
        }

        if (uiState.todayTransactions.isEmpty()) {
            item(key = "empty") {
                TodayEmptyCard()
            }
        } else {
            item(key = "today_list") {
                // 整组一个容器：流水是一份连续的清单，不该每条一张卡
                GroupedList(contentPadding = PaddingValues(vertical = Tokens.Spacing.xs)) {
                    uiState.todayTransactions.forEach { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            onClick = { transaction.id?.let { onItemClick(it) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 月度汇总 Hero 卡 —— 全 App 唯一使用 [AmountHero] 的地方。
 *
 * 结构自上而下：上下文标签 → 巨额金额 → 月度进度条 → 三格指标。
 * 整卡可点击跳统计页，右上角有 `›` 提示可点。
 */
@Composable
private fun MonthlySummaryCard(
    monthlyExpense: Int,
    monthlyIncome: Int,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val daysInMonth = today.lengthOfMonth()
    val daysPassed = today.dayOfMonth
    val daysLeft = daysInMonth - daysPassed
    val progress by animateFloatAsState(
        targetValue = daysPassed.toFloat() / daysInMonth,
        animationSpec = tween(Tokens.Motion.DURATION_MEDIUM),
        label = "monthProgress",
    )
    val dailyAvg = if (daysPassed > 0) monthlyExpense / daysPassed else 0
    val balance = monthlyIncome - monthlyExpense

    AppHeroCard(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // ---- 上下文行 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本月支出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${today.monthValue}月 · 剩 $daysLeft 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(Tokens.Spacing.xxs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(Tokens.IconSize.sm),
            )
        }

        // ---- 巨额金额 ----
        Spacer(Modifier.height(Tokens.Spacing.sm))
        AmountHero(
            amount = monthlyExpense,
            type = TransactionType.EXPENSE,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Tokens.Spacing.lg))

        // ---- 月度进度：时间维度 ----
        // 说明：没有预算数据时，「已花掉本月 29% 的额度」是误导。
        // 这里画的是「本月时间已过去多少」，并在右侧标注同一信息，
        // 让用户用它和自己的花钱速度做对比。
        MonthProgressBar(
            progress = progress,
            daysPassed = daysPassed,
            daysInMonth = daysInMonth,
        )

        Spacer(Modifier.height(Tokens.Spacing.lg))

        // ---- 三格指标 ----
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                label = "日均支出",
                value = AmountFormatter.toYuanDisplay(dailyAvg),
                valueStyle = AmountTypography.Stat,
            )
            if (monthlyIncome > 0) {
                StatCell(
                    label = "本月收入",
                    value = AmountFormatter.toCompactDisplay(monthlyIncome),
                    valueColor = MaterialTheme.semantic.income,
                    valueStyle = AmountTypography.Stat,
                    showDividerBefore = true,
                )
            }
            StatCell(
                label = if (balance >= 0) "结余" else "超支",
                value = AmountFormatter.toCompactDisplay(kotlin.math.abs(balance)),
                valueColor = if (balance >= 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.semantic.expense,
                valueStyle = AmountTypography.Stat,
                showDividerBefore = true,
            )
        }
    }
}

/**
 * 月度时间进度条。
 *
 * 4dp 细条 + 已过部分的实心填充 + 端点小圆点，一眼看出"今天走到哪了"。
 */
@Composable
private fun MonthProgressBar(
    progress: Float,
    daysPassed: Int,
    daysInMonth: Int,
) {
    val fillColor = MaterialTheme.colorScheme.primary
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                fillColor.copy(alpha = 0.55f),
                                fillColor,
                            ),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height(Tokens.Spacing.sm))
        Text(
            text = "本月已过 ${(progress * 100).toInt()}% · 第 $daysPassed/$daysInMonth 天",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 待同步横幅。
 *
 * 改造前是一个孤零零的 `SuggestionChip` 飘在页面中间，"⚠️ 3 笔待同步"里的
 * emoji 也和全局风格不一致。现在改成一张有图标、有说明、有动作的横幅，
 * 并且只在真的有内容时出现（AnimatedVisibility）。
 */
@Composable
private fun PendingSyncBanner(
    count: Int,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
) {
    val semantics = MaterialTheme.semantic
    val container = semantics.warningContainer
    val fg = semantics.onWarningContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(container)
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(Modifier.width(Tokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$count 笔还没同步",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            Text(
                text = "联网后会自动补上，也可以现在手动同步",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.75f),
            )
        }
        AppTextButton(
            text = if (isSyncing) "同步中" else "同步",
            onClick = onSyncClick,
            enabled = !isSyncing,
        )
    }
}

/**
 * 今日空态。
 *
 * 不再用「😌」emoji（跨设备字形不一致，且和全局视觉体系脱节），
 * 改为矢量图标 + 一句状态 + 一句解释这个 App 在干什么。
 */
@Composable
private fun TodayEmptyCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        EmptyState(
            icon = Icons.Default.Notifications,
            title = "今天还没有记账",
            subtitle = "通知监听开着，支付完成后会自动记到这里",
            modifier = Modifier.height(232.dp),
        )
    }
}
