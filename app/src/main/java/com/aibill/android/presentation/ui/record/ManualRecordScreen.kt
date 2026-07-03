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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
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
        val selectedCategory = state.categories.firstOrNull { it.id == state.selectedCategoryId }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 类型切换（固定顶部）
                TypeSelector(selectedType = state.type, onTypeSelected = viewModel::onTypeChanged)

                // 分类网格 / 转账账户（中部主区域，占据最大空间，可滚动）
                Box(modifier = Modifier.weight(1f).heightIn(min = 120.dp)) {
                    if (state.type == "transfer") {
                        Column(
                            modifier = Modifier.fillMaxSize()
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
                        RecordCategoryGrid(
                            categories = state.categories,
                            selectedId = state.selectedCategoryId,
                            onSelect = viewModel::onCategorySelected,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 底部输入面板：金额 + 备注 + 键盘聚合为一体（带圆角上边和阴影）
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 12.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        // 金额行：左=选中分类，右=金额大字
                        InputAmountRow(
                            selectedLabel = when {
                                state.type == "transfer" -> "转账"
                                selectedCategory != null -> "${selectedCategory.icon} ${selectedCategory.name}"
                                else -> "请选择分类"
                            },
                            amountText = state.amountText,
                            amountFen = state.amountFen,
                            type = state.type,
                        )
                        // 备注 + 日期
                        CompactNoteRow(
                            description = state.description,
                            date = state.date,
                            onDescriptionChanged = viewModel::onDescriptionChanged,
                            onDateChanged = viewModel::onDateChanged,
                        )
                        // 数字键盘
                        NumericKeyboard(
                            onInput = viewModel::onAmountInput, onDelete = viewModel::onAmountDelete,
                            onEquals = viewModel::onAmountEquals, onSave = viewModel::onSave,
                            isSaving = state.isSaving,
                        )
                    }
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
private fun InputAmountRow(
    selectedLabel: String,
    amountText: String,
    amountFen: Int,
    type: String,
) {
    val amountColor = when (type) {
        "income" -> IncomeColor
        "transfer" -> MaterialTheme.colorScheme.primary
        else -> ExpenseColor
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selectedLabel,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (amountText.isEmpty()) "¥0.00" else "¥$amountText",
                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-1).sp,
                color = amountColor,
            )
            if (amountText.contains(Regex("[+\\-*/]"))) {
                Text("= ${amountFen.toYuanDisplay()}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

@Composable
private fun CompactNoteRow(
    description: String,
    date: String,
    onDescriptionChanged: (String) -> Unit,
    onDateChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 备注输入（占主要宽度）
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChanged,
            placeholder = { Text("备注...", style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
        )
        // 日期显示（点击可改）
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(56.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 14.dp).fillMaxSize(),
            ) {
                Text(
                    text = date.takeLast(5), // MM-DD
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
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
