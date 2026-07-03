package com.aibill.android.presentation.ui.account

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibill.android.domain.model.Account
import com.aibill.android.presentation.theme.AppTextButton
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
        topBar = {
            TopAppBar(
                title = { Text("账户管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加账户")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无账户数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(accounts, key = { it.id }) { account ->
                    AccountItem(
                        account = account,
                        onClick = { editAccount = account },
                        onLongClick = { deleteAccount = account },
                    )
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

    // 停用确认弹窗
    deleteAccount?.let { acct ->
        AlertDialog(
            onDismissRequest = { deleteAccount = null },
            title = { Text("停用账户") },
            text = { Text("确定停用「${acct.name}」吗？停用后不会显示在记账选项中。") },
            confirmButton = {
                AppTextButton(
                    text = "停用",
                    onClick = {
                        viewModel.deleteAccount(acct.id)
                        deleteAccount = null
                    }
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deleteAccount = null })
            }
        )
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                )
                if (showType) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = accountTypes.firstOrNull { it.first == type }?.second ?: type,
                            onValueChange = {}, readOnly = true, label = { Text("类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
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
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { newVal ->
                        if (newVal.isEmpty() || newVal == "-" || newVal.matches(Regex("""^-?\d*\.?\d{0,2}$"""))) {
                            balance = newVal
                        }
                    },
                    label = { Text("初始余额 (元)") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountItem(
    account: Account,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = account.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = account.type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatBalance(account.currentBalance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatBalance(cents: Int): String {
    val yuan = cents / 100.0
    val formatter = NumberFormat.getCurrencyInstance(Locale.CHINA)
    return formatter.format(yuan)
}
