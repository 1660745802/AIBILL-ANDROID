package com.aibill.android.presentation.ui.record

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.utils.toYuanDisplay
import kotlinx.coroutines.delay

private val TYPE_TABS = listOf("expense" to "支出", "income" to "收入", "transfer" to "转账")

@OptIn(ExperimentalMaterial3Api::class)
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
                is ManualRecordViewModel.UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ManualRecordViewModel.UiEvent.SaveSuccess -> {
                    showSuccessIndicator = true
                }
            }
        }
    }

    LaunchedEffect(showSuccessIndicator) {
        if (showSuccessIndicator) { delay(1500L); showSuccessIndicator = false }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("记一笔", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // AI 快捷输入条（紧凑）
                AiQuickInput(
                    inputText = state.aiInputText,
                    isParsing = state.isAiParsing,
                    onInputChanged = viewModel::onAiInputChanged,
                    onParse = viewModel::onAiParse,
                )

                // 金额输入（大字，视觉焦点）
                AmountInputField(
                    amountText = state.amountText,
                    onAmountChanged = viewModel::onAmountTextChanged,
                    type = state.type,
                )

                // 类型切换
                TypeSelector(selectedType = state.type, onTypeSelected = viewModel::onTypeChanged)

                // 中部主区域
                if (state.type == "transfer") {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
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
                    // 分类Grid（占据剩余空间，内部可滚动）
                    Box(modifier = Modifier.weight(1f)) {
                        RecordCategoryGrid(
                            categories = state.categories,
                            selectedId = state.selectedCategoryId,
                            onSelect = viewModel::onCategorySelected,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 底部操作区：标签+备注+保存（不跟随键盘）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 标签（紧凑）
                    CompactTagRow(
                        tags = state.tags,
                        availableTags = state.availableTags,
                        onTagAdded = viewModel::onTagAdded,
                        onTagRemoved = viewModel::onTagRemoved,
                    )

                    // 备注
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = viewModel::onDescriptionChanged,
                        placeholder = { Text("备注（选填）", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        ),
                    )

                    // 保存按钮
                    PrimaryButton(
                        text = if (state.isSaving) "保存中..." else "保存",
                        onClick = viewModel::onSave,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AnimatedVisibility(
                visible = showSuccessIndicator, enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center),
            ) { SuccessOverlay() }
        }
    }
}

@Composable
private fun AmountInputField(
    amountText: String,
    onAmountChanged: (String) -> Unit,
    type: String,
) {
    val amountColor = when (type) {
        "income" -> IncomeColor
        "transfer" -> MaterialTheme.colorScheme.primary
        else -> ExpenseColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { newVal ->
                // 只允许数字和小数点，限制小数点后两位
                if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                    onAmountChanged(newVal)
                }
            },
            placeholder = {
                Text(
                    "0.00",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor,
                textAlign = TextAlign.Center,
            ),
            prefix = {
                Text(
                    "¥",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = amountColor,
                unfocusedBorderColor = amountColor.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun SuccessOverlay() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
            .padding(32.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle, contentDescription = "保存成功",
            modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("记录成功 ✓", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("继续记下一笔", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun TypeSelector(
    selectedType: String, onTypeSelected: (String) -> Unit, modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TYPE_TABS.forEach { (type, label) ->
            val isSelected = type == selectedType
            val bgColor = when {
                !isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
                type == "expense" -> ExpenseColor.copy(alpha = 0.12f)
                type == "income" -> IncomeColor.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val textColor = when {
                !isSelected -> MaterialTheme.colorScheme.onSurfaceVariant
                type == "expense" -> ExpenseColor
                type == "income" -> IncomeColor
                else -> MaterialTheme.colorScheme.primary
            }
            Surface(
                shape = RoundedCornerShape(14.dp), color = bgColor,
                modifier = Modifier.weight(1f).height(40.dp).clickable { onTypeSelected(type) },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactTagRow(
    tags: List<String>,
    availableTags: List<String>,
    onTagAdded: (String) -> Unit,
    onTagRemoved: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tagInput by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    val suggestions = remember(tagInput, availableTags, tags) {
        if (tagInput.isBlank()) availableTags.filter { it !in tags }.take(5)
        else availableTags.filter { it.contains(tagInput, ignoreCase = true) && it !in tags }.take(5)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 标签建议（一行横滑，在最上方）
        if (suggestions.isNotEmpty() && tags.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(suggestions.size) { index ->
                    val suggestion = suggestions[index]
                    SuggestionChip(
                        onClick = {
                            onTagAdded(suggestion)
                            tagInput = ""
                            showSuggestions = false
                        },
                        label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
        // 已选标签
        if (tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onTagRemoved(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除标签",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        // 输入框（最下方）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) {
            if (tagInput.isEmpty()) {
                Text("添加标签", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            androidx.compose.foundation.text.BasicTextField(
                value = tagInput,
                onValueChange = { value ->
                    tagInput = value
                    showSuggestions = true
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (tagInput.isNotBlank()) {
                        onTagAdded(tagInput)
                        tagInput = ""
                        showSuggestions = false
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferAccountSection(
    accounts: List<com.aibill.android.domain.model.Account> = emptyList(),
    selectedAccountId: Int? = null,
    selectedTargetAccountId: Int? = null,
    onAccountSelected: (Int) -> Unit = {},
    onTargetAccountSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "💡 转账不计入收支统计",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 来源账户
            var expandedFrom by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expandedFrom, onExpandedChange = { expandedFrom = !expandedFrom }) {
                OutlinedTextField(
                    value = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "选择来源账户",
                    onValueChange = {}, readOnly = true,
                    label = { Text("从") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedFrom) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expandedFrom, onDismissRequest = { expandedFrom = false }) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text("${account.icon} ${account.name}") },
                            onClick = { onAccountSelected(account.id); expandedFrom = false }
                        )
                    }
                }
            }

            // 目标账户
            var expandedTo by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expandedTo, onExpandedChange = { expandedTo = !expandedTo }) {
                OutlinedTextField(
                    value = accounts.firstOrNull { it.id == selectedTargetAccountId }?.name ?: "选择目标账户",
                    onValueChange = {}, readOnly = true,
                    label = { Text("到") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedTo) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expandedTo, onDismissRequest = { expandedTo = false }) {
                    accounts.filter { it.id != selectedAccountId }.forEach { account ->
                        DropdownMenuItem(
                            text = { Text("${account.icon} ${account.name}") },
                            onClick = { onTargetAccountSelected(account.id); expandedTo = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * AI 快捷填充输入条：输入自然语言，AI 自动填充表单字段。
 */
@Composable
private fun AiQuickInput(
    inputText: String,
    isParsing: Boolean,
    onInputChanged: (String) -> Unit,
    onParse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank()) onParse() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isParsing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 2.5.dp,
            )
        } else {
            androidx.compose.material3.FilledTonalButton(
                onClick = onParse,
                enabled = inputText.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text("AI填充", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
