package com.aibill.android.presentation.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.domain.model.Account
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.GroupedRow
import com.aibill.android.presentation.theme.AmountTypography
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManageScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AccountManageViewModel = hiltViewModel()
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editAccount by remember { mutableStateOf<Account?>(null) }
    var deleteAccount by remember { mutableStateOf<Account?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "账户管理", onBack = onBack) {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加账户")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        if (accounts.isEmpty()) {
            EmptyState(
                title = "还没有账户",
                subtitle = "添加现金、银行卡或支付宝账户，记账时就能选择资金来源",
                icon = Icons.Default.AccountBalanceWallet,
                actionText = "添加账户",
                onAction = { showAddDialog = true },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = Tokens.Spacing.screenHorizontal,
                    end = Tokens.Spacing.screenHorizontal,
                    top = Tokens.Spacing.md,
                    bottom = Tokens.Spacing.huge,
                ),
            ) {
                item(key = "group") {
                    GroupedList {
                        accounts.forEach { account ->
                            AccountRow(
                                account = account,
                                onClick = { editAccount = account },
                                onDelete = { deleteAccount = account },
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加账户弹窗
    if (showAddDialog) {
        AccountEditDialog(
            title = "添加账户",
            initialName = "",
            initialType = "cash",
            initialIcon = "💰",
            initialBalance = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, type, icon, balance ->
                viewModel.createAccount(name, type, icon, balance, 0)
                showAddDialog = false
            }
        )
    }

    // 编辑账户弹窗
    editAccount?.let { acct ->
        AccountEditDialog(
            title = "编辑账户",
            initialName = acct.name,
            initialType = acct.type,
            initialIcon = acct.icon,
            initialBalance = "%.2f".format(acct.currentBalance / 100.0),
            showType = false,
            onDismiss = { editAccount = null },
            onConfirm = { name, _, icon, balance ->
                viewModel.updateAccount(acct.id, name, icon, balance)
                editAccount = null
            }
        )
    }

    // 删除账户确认弹窗（PR #45：文案与实际行为对齐 — 实际是硬删非软删）
    deleteAccount?.let { acct ->
        AlertDialog(
            onDismissRequest = { deleteAccount = null },
            title = { Text("删除账户") },
            text = { Text("确定删除「${acct.name}」吗？该操作会从服务端删除，且不可撤销。") },
            confirmButton = {
                AppTextButton(
                    text = "删除",
                    onClick = {
                        viewModel.deleteAccount(acct.id)
                        deleteAccount = null
                    },
                    isDestructive = true,
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deleteAccount = null })
            }
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    GroupedRow(
        title = account.name,
        subtitle = accountTypeLabel(account.type),
        onClick = onClick,
        showChevron = false,
        leadingEmoji = account.icon,
        trailing = {
            Text(
                text = formatBalance(account.currentBalance),
                style = AmountTypography.Stat,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditDialog(
    title: String,
    initialName: String,
    initialType: String,
    initialIcon: String,
    initialBalance: String,
    showType: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, icon: String, balanceCents: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var icon by remember { mutableStateOf(initialIcon) }
    var balance by remember { mutableStateOf(initialBalance) }

    val accountTypes = listOf(
        "cash" to "现金", "bank" to "银行卡", "credit" to "信用卡",
        "alipay" to "支付宝", "wechat" to "微信"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md), modifier = Modifier.fillMaxWidth(),
                )
                if (showType) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = accountTypes.firstOrNull { it.first == type }?.second ?: type,
                            onValueChange = {}, readOnly = true, label = { Text("类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            shape = RoundedCornerShape(Tokens.Radius.md),
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            accountTypes.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { type = value; expanded = false }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = icon, onValueChange = { icon = it },
                    label = { Text("图标 (Emoji)") }, singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal == "-" || newVal.matches(Regex("""^-?\d*\.?\d{0,2}$"""))) {
                            balance = newVal
                        }
                    },
                    label = { Text("初始余额 (元)") }, singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            AppTextButton(
                text = "确定",
                onClick = {
                    if (name.isNotBlank()) {
                        val cents = ((balance.toDoubleOrNull() ?: 0.0) * 100).toInt()
                        onConfirm(name, type, icon, cents)
                    }
                },
                enabled = name.isNotBlank()
            )
        },
        dismissButton = {
            AppTextButton(text = "取消", onClick = onDismiss)
        }
    )
}

private fun accountTypeLabel(type: String): String = when (type) {
    "cash" -> "现金"
    "bank" -> "银行卡"
    "credit" -> "信用卡"
    "alipay" -> "支付宝"
    "wechat" -> "微信"
    else -> type
}

private fun formatBalance(cents: Int): String {
    val yuan = cents / 100.0
    val formatter = NumberFormat.getCurrencyInstance(Locale.CHINA)
    return formatter.format(yuan)
}
