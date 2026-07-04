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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.presentation.utils.toYuanDisplay

private val GradientStart = Color(0xFF009688)
private val GradientEnd = Color(0xFF4DB6AC)

@Composable
internal fun MonthlyExpenseHeader(amount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = amount.toYuanDisplay(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = (-0.5).sp,
            )
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
internal fun QuickPhraseRow(onPhraseClick: (String) -> Unit) {
    // PR #34：快捷短语带默认金额（PRD §5.2.1 一键短语 + 常见金额模板）
    // 用户点击后直接拿到"咖啡 30"这样的可解析文本，AI 能一次返回结果
    val phrases = listOf(
        "咖啡 30" to "☕",
        "午餐 25" to "🍜",
        "地铁 3" to "🚇",
        "早餐 10" to "🍳",
        "晚餐 35" to "🍽️",
        "超市 80" to "🛒",
    )
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
