package com.aibill.android.presentation.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens

@Composable
fun NumericKeyboard(
    onInput: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    modifier: Modifier = Modifier,
) {
    val keys = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf(".", "0", "⌫"),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.md, vertical = Tokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs + Tokens.Spacing.xs),
            ) {
                row.forEach { key ->
                    KeyboardButton(
                        label = key,
                        isOperator = false,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                "⌫" -> onDelete()
                                else -> onInput(key)
                            }
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Tokens.Spacing.xs + Tokens.Spacing.xs))
        PrimaryButton(
            text = if (isSaving) "保存中..." else "保存",
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KeyboardButton(
    label: String,
    isOperator: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        isOperator -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = modifier
            .height(Tokens.TouchTarget.normal - 2.dp)
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = if (isOperator) FontWeight.Bold else FontWeight.Medium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.outline
                isOperator -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
internal fun RecordCategoryGrid(
    categories: List<Category>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.md),
        contentPadding = PaddingValues(vertical = Tokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        items(categories, key = { it.id }) { category ->
            RecordCategoryItem(
                category = category,
                isSelected = category.id == selectedId,
                onClick = { onSelect(category.id) },
            )
        }
    }
}

@Composable
private fun RecordCategoryItem(
    category: Category,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Tokens.Spacing.xs + Tokens.Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = bgColor,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(text = category.icon, fontSize = 21.sp)
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
