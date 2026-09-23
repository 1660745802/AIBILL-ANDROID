package com.aibill.android.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aibill.android.domain.model.Account
import com.aibill.android.presentation.theme.Tokens

/**
 * 统一账户选择器。下拉式，**替代 Home + TransactionEditDialog 两份旧实现**。
 *
 * @param label "从" / "到" / 自定义
 * @param accounts 账户列表
 * @param selectedId 当前选中 ID（null 时显示 placeholder）
 * @param onSelect 选择回调
 * @param excludeId 排除的账户 ID（转账"从""到"互斥时使用）
 */
@Composable
fun AccountPicker(
    label: String,
    accounts: List<Account>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "选择账户",
    excludeId: Int? = null,
) {
    val selectableAccounts = remember(accounts, excludeId) {
        if (excludeId != null) accounts.filter { it.id != excludeId } else accounts
    }
    val selectedName = accounts
        .firstOrNull { it.id == selectedId }
        ?.let { "${it.icon} ${it.name}" }
        ?: placeholder

    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            singleLine = true,
            shape = RoundedCornerShape(Tokens.Radius.md),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            selectableAccounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("${account.icon} ${account.name}") },
                    onClick = {
                        onSelect(account.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
