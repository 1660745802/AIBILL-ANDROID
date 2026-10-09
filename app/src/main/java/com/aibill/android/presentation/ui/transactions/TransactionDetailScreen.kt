package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AmountInput
import com.aibill.android.presentation.components.AppCard
import com.aibill.android.presentation.components.AppHeroCard
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.GroupedDivider
import com.aibill.android.presentation.theme.PrimaryButtonBlock
import com.aibill.android.presentation.components.SectionHeader
import com.aibill.android.presentation.components.TypeSegmentedControl
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic
import com.aibill.android.presentation.ui.transactions.components.DetailAccountPickerRow
import com.aibill.android.presentation.ui.transactions.components.DetailCategoryPickerRow
import com.aibill.android.presentation.ui.transactions.components.DetailFieldBlock
import com.aibill.android.presentation.ui.transactions.components.DetailRow
import com.aibill.android.presentation.ui.transactions.components.DetailTagSection
import com.aibill.android.presentation.ui.transactions.components.DetailTextField
import kotlinx.coroutines.flow.collectLatest

/**
 * 交易详情页。**重设计**：
 * 旧版 9 张 `DetailCard` 竖直堆叠（label 里塞 emoji），滚动冗长、层级不清。
 * 现在重组为三块：
 * 1. **金额 Hero 区**（[AppHeroCard]）：大金额编辑 + 类型分段控件 + 分类名；
 *    这是进详情页第一眼要看/改的东西。
 * 2. **「基本信息」组**（一张 [AppCard]）：分类 / 账户 / 日期 / 时间，行间
 *    [GroupedDivider] 分隔；日期整行可点开 DatePicker。
 * 3. **「备注与标签」组**（第二张 [AppCard]）：描述输入 + 标签编辑。
 *
 * 「保存修改」按钮用 [Scaffold] 的 bottomBar 吸底，滚动时始终可点。
 * 所有 emoji label 换成矢量图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is TransactionDetailViewModel.UiEvent.ShowToast ->
                    snackbarHostState.showSnackbar(event.message)
                is TransactionDetailViewModel.UiEvent.NavigateBack ->
                    onNavigateBack()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "交易详情",
                onBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = viewModel::onDelete,
                        enabled = !uiState.isSaving,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.semantic.danger,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!uiState.isLoading) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PrimaryButtonBlock(
                        text = "保存修改",
                        onClick = viewModel::onSave,
                        enabled = !uiState.isSaving,
                        loading = uiState.isSaving,
                        modifier = Modifier.padding(
                            horizontal = Tokens.Spacing.screenHorizontal,
                            vertical = Tokens.Spacing.md,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(Tokens.Spacing.md))
                Text("加载中...", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = Tokens.Spacing.screenHorizontal,
                        vertical = Tokens.Spacing.md,
                    ),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
            ) {
                // ============ 1. 金额 Hero 区 ============
                AmountHeroSection(
                    type = uiState.type,
                    amount = uiState.amount,
                    categoryName = uiState.categoryName,
                    onTypeChanged = viewModel::onTypeChanged,
                    onAmountChanged = viewModel::onAmountChanged,
                )

                // ============ 2. 基本信息 ============
                SectionHeader(title = "基本信息")
                AppCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Tokens.Spacing.xs)) {
                    DetailFieldBlock(
                        icon = Icons.Outlined.Category,
                        label = "分类",
                    ) {
                        DetailCategoryPickerRow(
                            availableCategories = uiState.categories,
                            selectedCategoryId = uiState.categoryId,
                            onSelect = viewModel::onCategorySelected,
                        )
                    }
                    GroupedDivider()
                    DetailFieldBlock(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        label = "账户",
                    ) {
                        DetailAccountPickerRow(
                            availableAccounts = uiState.accounts,
                            selectedAccountId = uiState.accountId,
                            onSelect = viewModel::onAccountSelected,
                        )
                    }
                    GroupedDivider()
                    DetailRow(
                        icon = Icons.Outlined.CalendarMonth,
                        label = "日期",
                        value = uiState.date.ifBlank { "未设置" },
                        onClick = { showDatePicker = true },
                    )
                    GroupedDivider()
                    DetailFieldBlock(
                        icon = Icons.Outlined.Schedule,
                        label = "时间",
                    ) {
                        DetailTextField(
                            value = uiState.time,
                            onValueChange = viewModel::onTimeChanged,
                            placeholder = "HH:mm",
                        )
                    }
                }

                // ============ 3. 备注与标签 ============
                SectionHeader(title = "备注与标签")
                AppCard {
                    DetailFieldBlockInline(
                        icon = Icons.AutoMirrored.Outlined.Notes,
                        label = "描述",
                    ) {
                        DetailTextField(
                            value = uiState.description,
                            onValueChange = viewModel::onDescriptionChanged,
                            placeholder = "添加描述...",
                            singleLine = false,
                        )
                    }
                    Spacer(modifier = Modifier.height(Tokens.Spacing.md))
                    DetailFieldBlockInline(
                        icon = Icons.AutoMirrored.Outlined.Label,
                        label = "标签",
                    ) {
                        DetailTagSection(
                            tagsText = uiState.tags,
                            onTagsChanged = viewModel::onTagsChanged,
                            availableTags = uiState.availableTags,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
            }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.date.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        java.time.LocalDate.parse(it).toEpochDay() * 86_400_000L
                    }.getOrNull()
                }
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                        viewModel.onDateChanged(date)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

/**
 * 金额 Hero 区：类型分段控件 + 大金额输入 + 分类名。
 * 用 primaryContainer 作底，是全页唯一的重量级容器。
 */
@Composable
private fun AmountHeroSection(
    type: String,
    amount: String,
    categoryName: String,
    onTypeChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
) {
    AppHeroCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            vertical = Tokens.Spacing.lg,
        ),
    ) {
        TypeSegmentedControl(
            selected = type,
            onSelected = onTypeChanged,
        )
        AmountInput(
            value = amount,
            onValueChange = onAmountChanged,
            type = type,
        )
        if (categoryName.isNotBlank()) {
            Text(
                text = categoryName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = Tokens.Spacing.xs),
            )
        }
    }
}

/**
 * 「备注与标签」组内的字段块：图标 + label 在上，内容在下。
 * 与 [DetailFieldBlock] 相比不加外层行内边距（已在 AppCard 内），避免双重 padding。
 */
@Composable
private fun DetailFieldBlockInline(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
            Text(
                text = label,
                style = com.aibill.android.presentation.theme.AppTextStyles.ListTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(Tokens.Spacing.sm))
        content()
    }
}
