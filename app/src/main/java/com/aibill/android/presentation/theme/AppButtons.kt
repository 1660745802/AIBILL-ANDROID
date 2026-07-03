package com.aibill.android.presentation.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 全局统一按钮组件。所有页面的操作按钮应优先使用这里的组件，
 * 保证圆角、高度、字重、配色一致。
 *
 * 规范：
 * - 圆角统一 14dp
 * - 主按钮高度 52dp（页面主操作，如"保存"）
 * - 常规按钮高度 48dp
 * - 支持 loading 态与前置图标
 */

private val ButtonShape = RoundedCornerShape(14.dp)
private const val HEIGHT_PRIMARY = 52
private const val HEIGHT_NORMAL = 48

/** 主按钮：品牌主色填充，用于页面最重要的操作 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tall: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = (if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL).dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        ButtonContent(text, icon, loading, MaterialTheme.colorScheme.onPrimary)
    }
}

/** 次按钮：tonal 填充，用于次要但仍需强调的操作 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tall: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = (if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL).dp),
        shape = ButtonShape,
    ) {
        ButtonContent(text, icon, loading, MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** 描边按钮：用于取消、次要操作 */
@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tall: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = (if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL).dp),
        shape = ButtonShape,
    ) {
        ButtonContent(text, icon, false, MaterialTheme.colorScheme.primary)
    }
}

/** 文字按钮：用于对话框、弱操作 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = ButtonShape) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 危险按钮：删除等破坏性操作，用错误色 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    tall: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = (if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL).dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        ButtonContent(text, icon, loading, MaterialTheme.colorScheme.onError)
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.height(20.dp).width(20.dp),
            strokeWidth = 2.dp,
            color = contentColor,
        )
        Spacer(Modifier.width(8.dp))
    } else if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.height(18.dp).width(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
    )
}
