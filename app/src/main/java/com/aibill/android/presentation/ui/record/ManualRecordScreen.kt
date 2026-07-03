package com.aibill.android.presentation.ui.record

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.aibill.android.presentation.utils.toYuanDisplay
import kotlinx.coroutines.delay

private val TYPE_TABS = listOf("expense" to "支出", "income" to "收入", "transfer" to "转账")
private val ExpenseColor = Color(0xFFF44336)
private val IncomeColor = Color(0xFF4CAF50)

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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TypeSelector(selectedType = state.type, onTypeSelected = viewModel::onTypeChanged)
                AmountDisplay(amountText = state.amountText, amountFen = state.amountFen)
                RecordCategoryGrid(
                    categories = state.categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = viewModel::onCategorySelected,
                )
                ExpandableFields(
                    isExpanded = state.isExpanded, description = state.description,
                    date = state.date, onExpandToggle = viewModel::onExpandToggle,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onDateChanged = viewModel::onDateChanged,
                )
                Spacer(modifier = Modifier.height(16.dp))
                NumericKeyboard(
                    onInput = viewModel::onAmountInput, onDelete = viewModel::onAmountDelete,
                    onEquals = viewModel::onAmountEquals, onSave = viewModel::onSave,
                    isSaving = state.isSaving,
                )
            }
            AnimatedVisibility(
                visible = showSuccessIndicator, enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.Center),
            ) { SuccessOverlay() }
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
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
private fun AmountDisplay(amountText: String, amountFen: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (amountText.isEmpty()) "¥0.00" else "¥$amountText",
            fontSize = 42.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-1).sp,
        )
        if (amountText.contains(Regex("[+\\-*/]"))) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("= ${amountFen.toYuanDisplay()}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExpandableFields(
    isExpanded: Boolean, description: String, date: String,
    onExpandToggle: () -> Unit, onDescriptionChanged: (String) -> Unit,
    onDateChanged: (String) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onExpandToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更多信息", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description, onValueChange = onDescriptionChanged,
                    label = { Text("备注") }, modifier = Modifier.fillMaxWidth(),
                    maxLines = 2, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )
                OutlinedTextField(
                    value = date, onValueChange = onDateChanged,
                    label = { Text("日期 (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )
            }
        }
    }
}
