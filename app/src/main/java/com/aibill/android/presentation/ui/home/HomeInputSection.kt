package com.aibill.android.presentation.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.presentation.utils.toYuanDisplay
import java.time.LocalDate

private val GradientStart = Color(0xFF009688)
private val GradientEnd = Color(0xFF4DB6AC)

@Composable
internal fun MonthlyExpenseHeader(amount: Int, budget: Int? = null, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(GradientStart, GradientEnd),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                    shape = RoundedCornerShape(20.dp),
                )
                .padding(24.dp),
        ) {
            Text(
                text = "本月支出",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (budget != null && budget > 0) {
                // 有预算时显示: 支出金额 / 预算 xxx 元
                Row(
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = amount.toYuanDisplay(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = (-0.5).sp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "/ 预算 ${budget.toYuanDisplay()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 进度条
                val progress = (amount.toFloat() / budget).coerceIn(0f, 1.5f)
                val progressColor = when {
                    progress > 1f -> Color(0xFFEF5350) // 红色：超支
                    progress >= 0.8f -> Color(0xFFFFB74D) // 黄色：接近预算
                    else -> Color.White.copy(alpha = 0.9f) // 白色：正常
                }
                LinearProgressIndicator(
                    progress = { progress.coerceAtMost(1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = progressColor,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                // 日均消费
                if (amount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val dailyAvg = amount.toFloat() / LocalDate.now().dayOfMonth / 100f
                    Text(
                        text = "日均 ¥${"%.2f".format(dailyAvg)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            } else {
                Text(
                    text = amount.toYuanDisplay(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.5).sp,
                )
                // 日均消费
                if (amount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val dailyAvg = amount.toFloat() / LocalDate.now().dayOfMonth / 100f
                    Text(
                        text = "日均 ¥${"%.2f".format(dailyAvg)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AiInputSection(
    inputText: String,
    isParsing: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "✨ AI 智能记账",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "午餐 25、打车到公司 18…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    singleLine = true,
                    enabled = !isParsing,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (inputText.isNotBlank()) onSend() },
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            .copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme
                            .surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme
                            .surfaceContainerLow,
                    ),
                )
                Spacer(modifier = Modifier.width(12.dp))
                if (isParsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme
                                .surfaceContainerHighest,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickPhraseRow(
    phrases: List<Pair<String, String>>,
    onPhraseClick: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(phrases) { (text, icon) ->
            SuggestionChip(
                onClick = { onPhraseClick(text) },
                label = {
                    Text("$icon $text", style = MaterialTheme.typography.bodySmall)
                },
                shape = RoundedCornerShape(20.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                border = SuggestionChipDefaults.suggestionChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outlineVariant
                        .copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
internal fun MiniTrendChart(
    trendData: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    if (trendData.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 近 ${trendData.size} 天支出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val maxAmount = trendData.maxOf { it.second }.coerceAtLeast(1)
                trendData.forEach { (day, amount) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val barHeight = (amount.toFloat() / maxAmount * 36).coerceAtLeast(2f)
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(barHeight.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (amount == maxAmount) 1f else 0.6f
                                    ),
                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                ),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = day,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StreakChip(
    currentStreak: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    if (currentStreak <= 0 && totalCount <= 0) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentStreak > 0) {
            Text(
                text = "🔥 连续 $currentStreak 天",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (totalCount > 0) {
            Text(
                text = "📝 共 $totalCount 笔",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
