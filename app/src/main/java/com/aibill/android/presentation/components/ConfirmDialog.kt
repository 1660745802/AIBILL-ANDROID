package com.aibill.android.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.DangerButton
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.semantic

/**
 * 通用确认对话框。所有"是否 X"的二次确认都走这个组件。
 *
 * 用法：
 * ```
 * var showConfirm by remember { mutableStateOf(false) }
 * if (showConfirm) {
 *     ConfirmDialog(
 *         title = "退出登录",
 *         message = "退出后需重新登录...",
 *         confirmText = "退出",
 *         isDestructive = true,
 *         onConfirm = { ... },
 *         onDismiss = { showConfirm = false },
 *     )
 * }
 * ```
 *
 * @param isDestructive true 时确认按钮用错误色（破坏性操作）
 * @param cancelable 是否允许点击外部/返回键取消。默认 true
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = "取消",
    isDestructive: Boolean = false,
    cancelable: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (cancelable) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.xl),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = if (isDestructive) MaterialTheme.semantic.danger
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            // 破坏性操作的确认按钮用**实心** danger 色而不是文字按钮：
            // 文字按钮在一堆中性文字里太容易被忽略，而误点「删除/退出」代价很高。
            if (isDestructive) {
                DangerButton(text = confirmText, onClick = onConfirm)
            } else {
                PrimaryButton(text = confirmText, onClick = onConfirm, tall = false)
            }
        },
        dismissButton = {
            AppTextButton(text = cancelText, onClick = onDismiss)
        },
    )
}
