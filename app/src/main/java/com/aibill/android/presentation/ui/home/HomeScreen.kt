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
import androidx.compose.material.icons.filled.SyncProblem
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
import com.aibill.android.domain.model.SyncQueueStatus
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
                visible = !uiState.syncStatus.isEmpty,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PendingSyncBanner(
                    status = uiState.syncStatus,
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
 * 待同步横幅的档位。
 *
 * 由 [bannerVariantOf] 从 [SyncQueueStatus] 纯函数地推出——**枚举本身就是格式化决策的
 * 唯一判定点**，配色/图标/文案/按钮一律在 [PendingSyncBanner] 内部按它取。
 * 刻意不放进 domain：档位是排版概念，domain 里出现 BannerKind 就等于
 * 把文案写进了模型（见 [SyncQueueStatus] 的 KDoc）。
 */
internal enum class SyncBannerVariant {
    /** 两分区都是 0：不该出现（外层 AnimatedVisibility 已挡住） */
    Hidden,

    /** 只有 pending：排队等上传，会自动补上，可手动同步 */
    Pending,

    /** 只有 failed：本地终态，不会自动重试，且无任何处置入口 */
    Failed,

    /** 两者并存 */
    Mixed,
}

/**
 * 快照 → 档位。**展示层的纯函数**，不产生文案也不产生颜色。
 *
 * 分支顺序不能调换：`isEmpty` 是两分区同时为 0 的收口，
 * 而 Failed/Mixed 的判据是 `failed > 0`，必须先排除「无 failed」的纯 pending 档。
 */
internal fun bannerVariantOf(status: SyncQueueStatus): SyncBannerVariant = when {
    status.isEmpty -> SyncBannerVariant.Hidden
    status.failed == 0 -> SyncBannerVariant.Pending
    status.pending == 0 -> SyncBannerVariant.Failed
    else -> SyncBannerVariant.Mixed
}

/**
 * 待同步横幅。
 *
 * ## 硬约束：文案里不得出现任何用户当前没有 UI 入口执行的动作
 *
 * failed 行**没有任何逐行处置入口**：流水页的数据源是服务端（`TransactionsViewModel`
 * 的 `getTransactions`），本地 pending_transactions 明细在 presentation 层无任何展示 UI；
 * `deleteByClientId` 只被 SyncWorker 补偿与通知撤销调用；`deleteAll` 只被切服务器与
 * 换号登录调用。因此 Failed 档**只陈述状态与后果**，不写「去流水页手动删除」这类
 * 指不到入口的指引——那会是新谎报。处置能力待 failed 明细页（逐行重试/删除）解锁。
 *
 * ## 硬约束二：Failed 档不得主张任何**上传状态**
 *
 * 「已上传」「尚未上传」「可能已上传」都不能说。依据是本仓库自己的真相表
 * （`AutoRecordActionReceiver.handleUndo` 的 KDoc）：三条 `markFailed` 路径里
 * **只有 401**（`ERROR_TOKEN_EXPIRED`）能证明服务端明确拒绝、没建这笔；
 * 其余 `ERROR_MAX_RETRY_EXCEEDED`（5 次累积的是 IOException，请求可能已抵达、
 * 只是响应丢了）、`ERROR_SERVER_ANOMALY`（服务端 `code==0` 可能已建，
 * 只是 `created[]`/`duplicates[]` 没回显 clientId）、`ERROR_BUSINESS`
 * （服务端异常可能发生在落库之后）——服务端**都可能已经有这笔**。
 *
 * 而流水页的数据源正是服务端（`TransactionsViewModel.getTransactions`）。
 * 横幅若说「尚未上传」而用户在流水页看到这笔，就是自相矛盾，
 * 还可能诱导重复记账。所以副标题只讲「停止」这一件事，不碰上传状态。
 *
 * ## 为什么不给 Failed 档配 danger 颜色
 *
 * `dangerContainer` 的语义是**破坏性动作**（Colors.kt：「破坏性操作（删除、退出登录）」），
 * 是动作色；而本档没有任何动作，动作色 + 零动作 = 矛盾。档位区分由图标
 * （Sync → SyncProblem）与首句文案承担即可。等 failed 明细页（逐行重试/删除）
 * 给出逐行处置入口、本档拿到按钮之后，再升级为 danger 才与「有事可做」自洽。
 *
 * 配色/图标/文案/按钮全部在此函数内部按档位取，**不外传参数**：
 * 把 container/fg 提成调用方参数等于把格式化决策泄漏到上层。
 */
@Composable
internal fun PendingSyncBanner(
    status: SyncQueueStatus,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
) {
    val variant = bannerVariantOf(status)
    // 外层 AnimatedVisibility 已挡；直接调用时也不该渲染任何东西
    if (variant == SyncBannerVariant.Hidden) return

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
            imageVector = when (variant) {
                SyncBannerVariant.Pending -> Icons.Default.Sync
                // Failed/Mixed：同一个同步语义，但带问题标记——区分档位用，非警告色
                SyncBannerVariant.Failed,
                SyncBannerVariant.Mixed,
                -> Icons.Default.SyncProblem
                SyncBannerVariant.Hidden -> Icons.Default.Sync // 已在上方 return，不可达
            },
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(Modifier.width(Tokens.Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (variant) {
                    SyncBannerVariant.Pending -> "${status.pending} 笔还没同步"
                    SyncBannerVariant.Failed -> "${status.failed} 笔同步失败"
                    SyncBannerVariant.Mixed ->
                        "${status.pending} 笔待上传 · ${status.failed} 笔同步失败"
                    SyncBannerVariant.Hidden -> "" // 不可达
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            Text(
                text = when (variant) {
                    // 档 A 文案逐字保持现状：对 pending 而言「自动补上 / 手动同步」都成立
                    SyncBannerVariant.Pending -> "联网后会自动补上，也可以现在手动同步"
                    SyncBannerVariant.Failed -> "已停止自动重试"
                    SyncBannerVariant.Mixed -> "同步失败的不会自动重试"
                    SyncBannerVariant.Hidden -> "" // 不可达
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.75f),
            )
        }
        when (variant) {
            SyncBannerVariant.Pending -> AppTextButton(
                text = if (isSyncing) "同步中" else "同步",
                onClick = onSyncClick,
                enabled = !isSyncing,
            )
            // 作用域自明：只对应标题前半段的「待上传」，不暗示 failed 会被处理
            SyncBannerVariant.Mixed -> AppTextButton(
                text = if (isSyncing) "同步中" else "重试待上传",
                onClick = onSyncClick,
                enabled = !isSyncing,
            )
            // Failed：横幅是唯一手动同步入口，但 failed 不可被它重试——给按钮就是谎报动作
            SyncBannerVariant.Failed,
            SyncBannerVariant.Hidden,
            -> Unit
        }
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
