package com.aibill.android.presentation.ui.record

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AccountPicker
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.Pill
import com.aibill.android.presentation.components.PillTone
import com.aibill.android.presentation.components.TagEditor
import com.aibill.android.presentation.components.TypeSegmentedControl
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.onAccentFor
import com.aibill.android.presentation.theme.semantic
import kotlinx.coroutines.delay

/**
 * 手动记账页。
 *
 * ## 布局（自上而下是一条「输入 → 归类 → 确认」的流水线）
 *
 * ```
 *  ┌───────────────────────────────────┐
 *  │ ✕   记一笔                        │  关闭（模态流程，✕ 比 ← 更明确）
 *  ├───────────────────────────────────┤
 *  │ ✨ 午饭32 星巴克          [AI填充] │  自然语言快捷入口
 *  ├───────────────────────────────────┤
 *  │            ¥ 32.00                │  超大金额（40sp 等宽），不���输入框
 *  │      [支出] [收入] [转账]          │  类型切换，金额颜色跟着变
 *  ├───────────────────────────────────┤
 *  │  🍜   🍕   🚕   🛒   ☕   🎬      │  分类网格
 *  │ 餐饮  外食  交通  购物  咖啡  娱乐 │
 *  ├───────────────────────────────────┤
 *  │ 🏷 备注 · 📅 今天 · 👛 账户        │  低频字段收进 Sheet
 *  ├───────────────────────────────────┤
 *  │   7   8   9                        │
 *  │   4   5   6                        │  底部锚定数字键盘
 *  │   1   2   3                        │  （拇指可达，不弹系统键盘）
 *  │   .   0   ⌫                        │
 *  │      ┌──────────────┐              │
 *  │      │   记 账      │              │  保存按钮跟随类型语义色
 *  │      └──────────────┘              │
 *  └───────────────────────────────────┘
 * ```
 *
 * ## 关键改动
 * 1. **金额不再是输入框**，改为纯展示，由自绘键盘驱动 → 彻底消除「小数位自动补 00
 *    导致光标跳到末尾」的 bug 类，也让布局永远不会因软键盘而跳动。
 * 2. **保存按钮跟随类型着色**：记支出时是红、记收入时是绿。点之前就知道会记成什么。
 * 3. **保存按钮有真实禁用态**：金额为 0 或未选分类时置灰，并在上方给一行
 *    说明缺什么 —— 旧版是「按钮一直可点，点完弹 Toast」，用户白点一次。
 * 4. **成功后不清空整页**：只重置金额/分类，类型保持不变，方便连续记账。
 * 5. 标签、备注、日期、账户这些低频字段收进底部 Sheet，把纵向空间让给分类网格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRecordScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualRecordViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var justSaved by remember { mutableStateOf(false) }

    // 成功反馈：顶部内联提示条，而不是居中的大 overlay
    // （overlay 会挡住分类网格，用户想「接着记下一笔」时还被拦一下）
    var savedSummary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(1800L)
            justSaved = false
            savedSummary = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                // 成功条**只由 SaveSuccess 事件驱动**，不靠关键字匹配文案。
                // （曾用 message.contains("成功") 判断，而 VM 的「保存失败: <服务端
                // message>」里只要服务端 message 恰好含「成功」二字，就会把真实失败
                // 渲染成绿色成功条，用户以为记上了实际没记。）
                is ManualRecordViewModel.UiEvent.SaveSuccess -> {
                    savedSummary = event.message
                    justSaved = true
                }
                // ShowToast 一律走 Snackbar，不再吞掉。
                is ManualRecordViewModel.UiEvent.ShowToast ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val accent = when (state.type) {
        "income" -> MaterialTheme.semantic.income
        "transfer" -> MaterialTheme.semantic.transfer
        else -> MaterialTheme.semantic.expense
    }
    // 深色主题下 accent 是亮色，按钮文字必须用成对的深色前景，不能写死白字
    val onAccent = MaterialTheme.onAccentFor(state.type)

    val canSave = state.amountFen > 0 &&
        (state.type == "transfer" || state.selectedCategoryId != null)

    val blockingHint = when {
        state.amountFen <= 0 -> "输入金额后即可保存"
        state.type != "transfer" && state.selectedCategoryId == null -> "选一个分类"
        else -> null
    }

    var showDetailSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "记一笔",
                onBack = onNavigateBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // ============ AI 快捷输入 ============
            AiQuickInputBar(
                inputText = state.aiInputText,
                isParsing = state.isAiParsing,
                onInputChanged = viewModel::onAiInputChanged,
                onParse = viewModel::onAiParse,
            )

            // ============ 金额 + 类型 ============
            AmountDisplayPanel(
                amountText = state.amountText,
                type = state.type,
                accent = accent,
            )

            TypeSegmentedControl(
                selected = state.type,
                onSelected = viewModel::onTypeChanged,
            )

            Spacer(Modifier.height(Tokens.Spacing.sm))

            // ============ 分类网格 / 转账账户 ============
            if (state.type == "transfer") {
                TransferPanel(
                    accounts = state.accounts,
                    selectedAccountId = state.accountId,
                    selectedTargetAccountId = state.targetAccountId,
                    onAccountSelected = viewModel::onAccountSelected,
                    onTargetAccountSelected = viewModel::onTargetAccountSelected,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            } else if (state.categories.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    NoCategoriesHint()
                }
            } else {
                RecordCategoryGrid(
                    categories = state.categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = viewModel::onCategorySelected,
                    modifier = Modifier.weight(1f),
                )
            }

            // ============ 低频字段入口 ============
            DetailsBar(
                description = state.description,
                tags = state.tags,
                accountLabel = state.accounts
                    .firstOrNull { it.id == state.accountId }?.name,
                onClick = { showDetailSheet = true },
            )

            // ============ 数字键盘 + 保存 ============
            AnimatedVisibility(
                visible = savedSummary != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                SavedBanner(text = savedSummary.orEmpty())
            }

            NumericKeypad(
                onInput = viewModel::onAmountInput,
                onDelete = viewModel::onAmountDelete,
                enabled = !state.isSaving,
            )

            SaveBar(
                canSave = canSave,
                isSaving = state.isSaving,
                hint = blockingHint,
                accent = accent,
                onAccent = onAccent,
                onSave = viewModel::onSave,
            )
        }
    }

    // ============ 低频字段 Sheet ============
    if (showDetailSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showDetailSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            DetailsSheet(
                state = state,
                onDescriptionChanged = viewModel::onDescriptionChanged,
                onTagAdded = viewModel::onTagAdded,
                onTagRemoved = viewModel::onTagRemoved,
                onAccountSelected = viewModel::onAccountSelected,
            )
        }
    }
}

/**
 * 金额展示区。
 *
 * 关键：**不是 TextField**。40sp 等宽数字 + 小一号的 ¥ 符号，垂直居中。
 * 没有聚焦态、没有光标、没有输入法，纯粹由键盘驱动 —— 视觉焦点唯一且明确。
 */
@Composable
private fun AmountDisplayPanel(
    amountText: String,
    type: String,
    accent: Color,
) {
    val typeLabel = when (type) {
        "income" -> "收入金额"
        "transfer" -> "转账金额"
        else -> "支出金额"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(Tokens.Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "¥",
                style = AmountTypography.Input.copy(
                    fontSize = AmountTypography.Input.fontSize * 0.55f,
                    color = accent.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(bottom = 6.dp, end = 2.dp),
            )
            if (amountText.isEmpty()) {
                Text(
                    text = "0.00",
                    style = AmountTypography.Input.copy(color = accent.copy(alpha = 0.25f)),
                )
            } else {
                Text(
                    text = amountText,
                    style = AmountTypography.Input.copy(color = accent),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 底部保存栏。
 *
 * 两个要点：
 * - 按钮底色跟随业务类型（支出红 / 收入绿 / 转账中性），**按下之前就预告了结果**
 * - 禁用时按钮下方给一行「还差什么」，而不是让用户点了之后被 Toast 拒绝
 */
@Composable
private fun SaveBar(
    canSave: Boolean,
    isSaving: Boolean,
    hint: String?,
    accent: Color,
    onAccent: Color,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(
                start = Tokens.Spacing.lg,
                end = Tokens.Spacing.lg,
                top = Tokens.Spacing.md,
                bottom = Tokens.Spacing.md,
            ),
    ) {
        // 高度固定的提示行：有无提示时布局不跳动
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hint != null && !isSaving) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Text(
                    text = "保存后可直接继续记下一笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(Tokens.Spacing.sm))
        SaveButton(
            canSave = canSave && !isSaving,
            isSaving = isSaving,
            accent = accent,
            onAccent = onAccent,
            onClick = onSave,
        )
    }
}

@Composable
private fun SaveButton(
    canSave: Boolean,
    isSaving: Boolean,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (canSave) accent else accent.copy(alpha = 0.18f),
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "saveBtnBg",
    )
    val content by animateColorAsState(
        // 成对前景色：深色主题下按钮底是亮色，白字会看不见
        targetValue = if (canSave) onAccent else onAccent.copy(alpha = 0.55f),
        animationSpec = tween(Tokens.Motion.DURATION_FAST),
        label = "saveBtnFg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Tokens.TouchTarget.normal)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(container)
            .clickable(enabled = canSave, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = content,
            )
        } else {
            Text(
                text = "记 账",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }
    }
}

/**
 * 成功后出现在键盘上方的内联提示条。
 *
 * 告诉用户「记了什么」而不只是「成功了」，并且明确下一步可以继续记账。
 */
@Composable
private fun SavedBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(MaterialTheme.semantic.incomeContainer)
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.semantic.income,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.semantic.onIncomeContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "继续记",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.semantic.onIncomeContainer.copy(alpha = 0.7f),
        )
    }
}

/**
 * 低频字段入口条。点任意位置打开详情 Sheet。
 */
@Composable
private fun DetailsBar(
    description: String,
    tags: List<String>,
    accountLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg)
            .height(Tokens.TouchTarget.normal)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Label,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.IconSize.sm),
        )
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Text(
            text = when {
                description.isNotBlank() -> description
                tags.isNotEmpty() -> tags.joinToString(" ") { "#$it" }
                accountLabel != null -> accountLabel
                else -> "备注 · 标签 · 账户"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (description.isNotBlank() || tags.isNotEmpty()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tags.isNotEmpty() || description.isNotBlank() || accountLabel != null) {
            Pill(text = "编辑", tone = PillTone.Neutral)
        }
    }
}

/**
 * 转账模式的面板：转账不计入收支统计，所以需要明确告知。
 */
@Composable
private fun TransferPanel(
    accounts: List<com.aibill.android.domain.model.Account>,
    selectedAccountId: Int?,
    selectedTargetAccountId: Int?,
    onAccountSelected: (Int) -> Unit,
    onTargetAccountSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.Radius.md))
                .background(MaterialTheme.semantic.transferContainer)
                .padding(Tokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "转账不计入收支统计，只改变账户余额",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semantic.transfer,
            )
        }
        AccountPicker(
            label = "转出账户",
            accounts = accounts,
            selectedId = selectedAccountId,
            onSelect = onAccountSelected,
            placeholder = "选择转出账户",
        )
        AccountPicker(
            label = "转入账户",
            accounts = accounts,
            selectedId = selectedTargetAccountId,
            onSelect = onTargetAccountSelected,
            placeholder = "选择转入账户",
            excludeId = selectedAccountId,
        )
    }
}

/**
 * 低频字段 Sheet：备注 / 标签 / 账户 / 日期。
 */
@Composable
private fun DetailsSheet(
    state: ManualRecordViewModel.RecordUiState,
    onDescriptionChanged: (String) -> Unit,
    onTagAdded: (String) -> Unit,
    onTagRemoved: (String) -> Unit,
    onAccountSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Tokens.Spacing.xl,
                end = Tokens.Spacing.xl,
                bottom = Tokens.Spacing.xxxl,
            ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailHint(Icons.AutoMirrored.Filled.EventNote, "日期", state.date)
            DetailHint(Icons.AutoMirrored.Filled.Label, "来源", "手动记账")
        }

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            label = { Text("备注") },
            placeholder = { Text("这笔钱花在哪儿了") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(Tokens.Radius.sm),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        )

        TagEditor(
            tags = state.tags,
            availableTags = state.availableTags,
            onTagsChanged = { newTags ->
                val added = newTags - state.tags.toSet()
                val removed = state.tags - newTags.toSet()
                added.forEach { onTagAdded(it) }
                removed.forEach { onTagRemoved(it) }
            },
            label = "标签",
            placeholder = "添加标签",
        )

        if (state.type != "transfer") {
            AccountPicker(
                label = "账户",
                accounts = state.accounts,
                selectedId = state.accountId,
                onSelect = onAccountSelected,
                placeholder = "不指定账户",
            )
        }
    }
}

@Composable
private fun DetailHint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.IconSize.sm),
        )
        Spacer(Modifier.width(Tokens.Spacing.xs))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * AI 快捷输入条。
 *
 * 「✨ 说一句话快速填充…」改成图标 + 更具体的示例，
 * 并把「AI填充」按钮文案改成「解析」，动词更准确。
 */
@Composable
private fun AiQuickInputBar(
    inputText: String,
    isParsing: Boolean,
    onInputChanged: (String) -> Unit,
    onParse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            placeholder = {
                Text(
                    "午饭 32 星巴克",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Tokens.IconSize.sm),
                )
            },
            singleLine = true,
            enabled = !isParsing,
            shape = RoundedCornerShape(Tokens.Radius.sm),
            // 默认 OutlinedTextField 高 56dp，这里压到 48dp（= 触控目标下限），
            // 把省下的高度让给分类选择区
            modifier = Modifier
                .weight(1f)
                .height(Tokens.TouchTarget.normal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (inputText.isNotBlank()) onParse() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        Spacer(Modifier.width(Tokens.Spacing.sm))
        Box(
            modifier = Modifier
                .height(Tokens.TouchTarget.normal)
                .clip(RoundedCornerShape(Tokens.Radius.sm))
                .background(
                    if (inputText.isNotBlank() && !isParsing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .clickable(enabled = inputText.isNotBlank() && !isParsing, onClick = onParse)
                .padding(horizontal = Tokens.Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            if (isParsing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "解析",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
