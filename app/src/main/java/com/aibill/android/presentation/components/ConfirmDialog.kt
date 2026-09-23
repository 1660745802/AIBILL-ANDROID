package com.aibill.android.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.aibill.android.presentation.theme.AppTextButton

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
        title = {
            Text(
                title,
                color = if (isDestructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = { Text(message) },
        confirmButton = {
            AppTextButton(
                text = confirmText,
                onClick = onConfirm,
                isDestructive = isDestructive,
            )
        },
        dismissButton = {
            AppTextButton(text = cancelText, onClick = onDismiss)
        },
    )
}
