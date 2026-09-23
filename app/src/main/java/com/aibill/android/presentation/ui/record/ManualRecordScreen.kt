package com.aibill.android.presentation.ui.record

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.components.AmountInput
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.TagEditor
import com.aibill.android.presentation.components.TypeSegmentedControl
import com.aibill.android.presentation.components.AccountPicker
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens
import kotlinx.coroutines.delay

/**
 * 手动记账页。**已重构**：使用 UI Kit 替换 5 处私有重复实现。
 *
 * 保留：
 * - SuccessOverlay（保存成功的视觉反馈）
 * - AiQuickInput（AI 自然语言填充）
 */
@Composable
fun ManualRecordScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualRecordViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSuccessIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ManualRecordViewModel.UiEvent.ShowToast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                is ManualRecordViewModel.UiEvent.SaveSuccess ->
                    showSuccessIndicator = true
            }
        }
    }

    LaunchedEffect(showSuccessIndicator) {
        if (showSuccessIndicator) {
            delay(1500L)
            showSuccessIndicator = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar(title = "记一笔", onBack = onNavigateBack) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                AiQuickInput(
                    inputText = state.aiInputText,
                    isParsing = state.isAiParsing,
                    onInputChanged = viewModel::onAiInputChanged,
                    onParse = viewModel::onAiParse,
                )

                // 金额输入（视觉焦点，大字）
                AmountInput(
                    amountFen = state.amountFen,
                    onAmountChange = { fen ->
                        // 受控 amountFen + amountText 双向：金额变化时回写字符串以保证外部逻辑兼容
                        viewModel.onAmountTextChanged(formatFenToText(fen))
                    },
                    type = state.type,
                )

                TypeSegmentedControl(
                    selected = state.type,
                    onSelected = viewModel::onTypeChanged,
                )

                if (state.type == "transfer") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md),
                    ) {
                        TransferAccountSection(
                            accounts = state.accounts,
                            selectedAccountId = state.accountId,
                            selectedTargetAccountId = state.targetAccountId,
                            onAccountSelected = viewModel::onAccountSelected,
                            onTargetAccountSelected = viewModel::onTargetAccountSelected,
                        )
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        RecordCategoryGrid(
                            categories = state.categories,
                            selectedId = state.selectedCategoryId,
                            onSelect = viewModel::onCategorySelected,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 底部操作区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
                ) {
                    TagEditor(
                        tags = state.tags,
                        availableTags = state.availableTags,
                        onTagsChanged = { newTags ->
                            // 增量更新（受控）
                            val added = newTags - state.tags.toSet()
                            val removed = state.tags - newTags.toSet()
                            added.forEach { viewModel.onTagAdded(it) }
                            removed.forEach { viewModel.onTagRemoved(it) }
                        },
                        label = "标签",
                        placeholder = "添加标签",
                    )

                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChanged,
                        placeholder = { Text("备注（选填）", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(Tokens.Radius.md),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        ),
                    )

                    PrimaryButton(
                        text = if (state.isSaving) "保存中..." else "保存",
                        onClick = viewModel::onSave,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AnimatedVisibility(
                visible = showSuccessIndicator,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                SuccessOverlay()
            }
        }
    }
}

private fun formatFenToText(fen: Int): String =
    if (fen <= 0) "" else "%.2f".format(fen / 100.0)

@Composable
private fun SuccessOverlay() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Radius.xl))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
            .padding(Tokens.Spacing.xxxl),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "保存成功",
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(Tokens.Spacing.md))
        Text(
            "记录成功 ✓",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            "继续记下一笔",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun TransferAccountSection(
    accounts: List<com.aibill.android.domain.model.Account> = emptyList(),
    selectedAccountId: Int? = null,
    selectedTargetAccountId: Int? = null,
    onAccountSelected: (Int) -> Unit = {},
    onTargetAccountSelected: (Int) -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            Text(
                "💡 转账不计入收支统计",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccountPicker(
                label = "从",
                accounts = accounts,
                selectedId = selectedAccountId,
                onSelect = onAccountSelected,
                placeholder = "选择来源账户",
            )
            AccountPicker(
                label = "到",
                accounts = accounts,
                selectedId = selectedTargetAccountId,
                onSelect = onTargetAccountSelected,
                placeholder = "选择目标账户",
                excludeId = selectedAccountId,
            )
        }
    }
}

@Composable
private fun AiQuickInput(
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
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "✨ 说一句话快速填充…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            singleLine = true,
            enabled = !isParsing,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(Tokens.Radius.md),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank()) onParse() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(modifier = Modifier.width(Tokens.Spacing.sm))
        if (isParsing) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 2.5.dp,
            )
        } else {
            FilledTonalButton(
                onClick = onParse,
                enabled = inputText.isNotBlank(),
                modifier = Modifier.height(Tokens.TouchTarget.xlarge),
                shape = RoundedCornerShape(Tokens.Radius.md),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Tokens.Spacing.lg),
            ) {
                Text("AI填充", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
