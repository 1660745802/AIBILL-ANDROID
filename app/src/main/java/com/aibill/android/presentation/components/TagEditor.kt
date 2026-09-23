package com.aibill.android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一标签编辑器。**替代 Home/TransactionEditDialog/ManualRecordScreen 三份旧实现**。
 *
 * 行为：
 * - 已选标签（InputChip，点击移除）
 * - 输入框（回车/逗号/Done 提交）
 * - 建议列表（SuggestionChip，点击添加）
 *
 * 用法（受控）：
 * ```
 * var tags by remember { mutableStateOf(listOf<String>()) }
 * TagEditor(
 *     tags = tags,
 *     availableTags = listOf("早餐", "午餐", "晚餐"),
 *     onTagsChanged = { tags = it },
 * )
 * ```
 *
 * @param label "标签" / "Tags" / 自定义
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    tags: List<String>,
    availableTags: List<String>,
    onTagsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "标签",
    placeholder: String = "添加标签",
    maxSuggestions: Int = 5,
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val suggestions = remember(input, availableTags, tags) {
        if (input.isBlank()) {
            availableTags.filter { it !in tags }.take(maxSuggestions)
        } else {
            availableTags
                .filter { it.contains(input, ignoreCase = true) && it !in tags }
                .take(maxSuggestions)
        }
    }

    fun commitInput() {
        val trimmed = input.trim()
        if (trimmed.isNotBlank() && trimmed !in tags) {
            onTagsChanged(tags + trimmed)
        }
        input = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Tokens.Spacing.xs))

        // 已选标签 + 输入框
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        ) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onTagsChanged(tags - tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(Tokens.IconSize.xs),
                        )
                    },
                    modifier = Modifier.heightIn(min = 28.dp),
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .heightIn(min = Tokens.TouchTarget.small),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.sm),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onDone = { commitInput() }),
            )
        }

        // 建议
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.Spacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs / 2),
            ) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            if (suggestion !in tags) {
                                onTagsChanged(tags + suggestion)
                            }
                            input = ""
                        },
                        label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        ),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}
