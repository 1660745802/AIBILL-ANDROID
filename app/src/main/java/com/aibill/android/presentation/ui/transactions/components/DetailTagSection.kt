package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aibill.android.presentation.theme.Tokens

/**
 * 详情页标签编辑区。**保留逗号分隔字符串接口**，与 VM 当前契约兼容。
 *
 * 若后续 VM 改为 List<String>，可直接替换为 [com.aibill.android.presentation.components.TagEditor]。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailTagSection(
    tagsText: String,
    onTagsChanged: (String) -> Unit,
    availableTags: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val tags = remember(tagsText) {
        tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    var tagInput by remember { mutableStateOf("") }
    val suggestions = remember(availableTags, tags) {
        availableTags.filter { it !in tags }.take(5)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 建议（仅当已有标签时显示）
        if (suggestions.isNotEmpty() && tags.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Tokens.Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
            ) {
                items(suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = {
                            val newTags = tags + suggestion
                            onTagsChanged(newTags.joinToString(", "))
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

        // 已选标签
        if (tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {
                            onTagsChanged((tags - tag).joinToString(", "))
                        },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除标签",
                                modifier = Modifier.size(Tokens.IconSize.xs),
                            )
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(Tokens.Spacing.xs))
        }

        // 输入框
        OutlinedTextField(
            value = tagInput,
            onValueChange = { tagInput = it },
            placeholder = { Text("添加标签", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(Tokens.Radius.sm),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val trimmed = tagInput.trim()
                if (trimmed.isNotBlank() && trimmed !in tags) {
                    onTagsChanged((tags + trimmed).joinToString(", "))
                }
                tagInput = ""
            }),
        )
    }
}
