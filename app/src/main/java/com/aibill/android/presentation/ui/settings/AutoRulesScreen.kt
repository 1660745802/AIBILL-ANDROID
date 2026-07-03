package com.aibill.android.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.data.local.entity.CategoryRuleEntity
import com.aibill.android.presentation.theme.AppTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRulesScreen(
    onBack: () -> Unit = {},
    viewModel: AutoRulesViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能免确认") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // 自动化程度
            item { AutomationLevelCard(uiState.automationLevel, viewModel::onAutomationLevelChanged) }

            // 已学习规则标题
            item {
                Text(
                    text = "已学习的规则",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (uiState.learnedRules.isEmpty()) {
                item { EmptyRulesHint() }
            } else {
                items(uiState.learnedRules, key = { it.keyword }) { rule ->
                    LearnedRuleItem(rule = rule, onDelete = { viewModel.onDeleteRule(rule.keyword) })
                }
            }

            // 小额免确认
            item {
                SmallAmountCard(
                    enabled = uiState.smallAmountEnabled,
                    thresholdCents = uiState.smallAmountThreshold,
                    onToggle = viewModel::onSmallAmountToggle,
                    onThresholdChanged = viewModel::onThresholdChanged
                )
            }
        }
    }
}

@Composable
private fun AutomationLevelCard(
    level: String,
    onLevelChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("自动化程度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (level) {
                    "conservative" -> "保守模式：每笔都需确认"
                    "aggressive" -> "激进模式：尽可能自动入库"
                    else -> "标准模式：智能判断（推荐）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            val sliderValue = when (level) {
                "conservative" -> 0f
                "aggressive" -> 1f
                else -> 0.5f
            }
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    val newLevel = when {
                        value < 0.33f -> "conservative"
                        value > 0.66f -> "aggressive"
                        else -> "standard"
                    }
                    onLevelChanged(newLevel)
                },
                steps = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("保守", style = MaterialTheme.typography.labelSmall)
                Text("标准", style = MaterialTheme.typography.labelSmall)
                Text("激进", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LearnedRuleItem(
    rule: CategoryRuleEntity,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.keyword, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "命中 ${rule.hitCount} 次 · 分类ID: ${rule.categoryId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppTextButton(text = "删除", onClick = onDelete)
        }
    }
}

@Composable
private fun SmallAmountCard(
    enabled: Boolean,
    thresholdCents: Int,
    onToggle: (Boolean) -> Unit,
    onThresholdChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("小额免确认", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "¥${thresholdCents / 100} 以下消费自动入库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                var textValue by remember(thresholdCents) {
                    mutableStateOf((thresholdCents / 100).toString())
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input
                        val yuan = input.toIntOrNull() ?: return@OutlinedTextField
                        if (yuan in 1..1000) {
                            onThresholdChanged(yuan * 100)
                        }
                    },
                    label = { Text("阈值（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyRulesHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🤖", style = MaterialTheme.typography.displaySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "还没有学习到规则",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "使用 App 记账后系统会自动学习你的习惯",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
