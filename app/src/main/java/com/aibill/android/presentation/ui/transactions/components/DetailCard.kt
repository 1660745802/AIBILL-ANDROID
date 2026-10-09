package com.aibill.android.presentation.ui.transactions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.aibill.android.presentation.theme.AppTextStyles
import com.aibill.android.presentation.theme.Tokens

/**
 * 详情页「字段行」。**重设计**：
 * 旧版每个字段一张 `DetailCard(label = "💰 类型")`，label 里塞 emoji、9 张卡竖排。
 * 现在改为一张 [com.aibill.android.presentation.components.AppCard] 内多行，
 * 每行是本 [DetailRow]：左侧矢量图标 + label，右侧值（或自定义 trailing），
 * 整行可点（如日期行点开 DatePicker）。
 *
 * @param icon 左侧矢量图标（禁止 emoji）
 * @param label 字段名
 * @param value 字段值文本（当未提供 [trailing] 时显示在右侧）
 * @param onClick 整行点击（null 表示不可点，无按压反馈）
 * @param trailing 自定义右侧内容，优先于 [value]
 */
@Composable
fun DetailRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Tokens.TouchTarget.xlarge)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.IconSize.md),
        )
        Spacer(modifier = Modifier.width(Tokens.Spacing.md))
        Text(
            text = label,
            style = AppTextStyles.ListTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 详情页「块内字段」容器：一个 label + 下方自定义内容（如 chip 选择器）。
 * 用于分类 / 账户这类需要平铺多个选项、不适合单行展示的字段。
 */
@Composable
fun DetailFieldBlock(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.IconSize.md),
            )
            Text(
                text = label,
                style = AppTextStyles.ListTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.size(Tokens.Spacing.sm))
        content()
    }
}

/**
 * 详情页输入框：弱化边框、圆角 14dp。
 */
@Composable
fun DetailTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        shape = RoundedCornerShape(Tokens.Radius.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        ),
    )
}
