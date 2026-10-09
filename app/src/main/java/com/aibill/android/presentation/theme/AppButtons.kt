package com.aibill.android.presentation.theme


import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * - **按下时高度收缩 2dp + 透明度降低**（物理反馈，用户不看屏幕也知道按到了）
 */

private val ButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.md)
private const val HEIGHT_PRIMARY = 52
private const val HEIGHT_NORMAL = 44

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
    val base = if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL
    val h by animateButtonHeight(base, enabled && !loading)
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = h),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Tokens.Spacing.xl,
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
    val base = if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL
    val h by animateButtonHeight(base, enabled && !loading)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = h),
        shape = ButtonShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Tokens.Spacing.lg,
        ),
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
    val base = if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL
    val h by animateButtonHeight(base, enabled)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = h),
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        border = androidx.compose.foundation.BorderStroke(
            Tokens.Border.thin,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Tokens.Spacing.lg,
        ),
    ) {
        ButtonContent(text, icon, false, MaterialTheme.colorScheme.primary)
    }
}

/** 文字按钮：用于对话框、弱操作。isDestructive=true 时用错误色标示破坏性操作 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Tokens.TouchTarget.small),
        shape = ButtonShape,
        colors = if (isDestructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
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
    val base = if (tall) HEIGHT_PRIMARY else HEIGHT_NORMAL
    val h by animateButtonHeight(base, enabled && !loading)
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = h),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Tokens.Spacing.xl,
        ),
    ) {
        ButtonContent(text, icon, loading, MaterialTheme.colorScheme.onError)
    }
}

/**
 * 按钮按压反馈：按下时高度收缩 2dp。
 *
 * Material3 默认按钮没有形变反馈，只靠颜色变化——在快速连点的记账场景里，
 * 高度收缩的物理感比颜色变化更容易被余光捕捉到。
 */
@Composable
private fun animateButtonHeight(base: Int, active: Boolean) =
    animateDpAsState(
        targetValue = if (active) (base - 2).dp else base.dp,
        animationSpec = androidx.compose.animation.core.tween(Tokens.Motion.DURATION_INSTANT),
        label = "buttonPressHeight",
    )

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (icon != null || loading) {
                androidx.compose.foundation.layout.Spacer(
                    Modifier.size(Tokens.Spacing.sm),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/** 常用：整宽主按钮（移动端表单底部标准写法） */
@Composable
fun PrimaryButtonBlock(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    PrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        loading = loading,
        icon = icon,
        tall = true,
    )
}
