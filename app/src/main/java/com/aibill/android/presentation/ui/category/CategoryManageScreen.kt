package com.aibill.android.presentation.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.aibill.android.domain.model.Category
import com.aibill.android.presentation.components.AppTopBar
import com.aibill.android.presentation.components.CategoryAvatar
import com.aibill.android.presentation.components.EmptyState
import com.aibill.android.presentation.components.GroupedList
import com.aibill.android.presentation.components.GroupedRow
import com.aibill.android.presentation.components.Pill
import com.aibill.android.presentation.components.PillTone
import com.aibill.android.presentation.components.SegmentedControl
import com.aibill.android.presentation.theme.AppTextButton
import com.aibill.android.presentation.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CategoryManageViewModel = hiltViewModel()
) {
    val expenseCategories by viewModel.expenseCategories.collectAsStateWithLifecycle()
    val incomeCategories by viewModel.incomeCategories.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf("expense") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<Category?>(null) }
    var deleteCategory by remember { mutableStateOf<Category?>(null) }
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
            AppTopBar(title = "分类管理", onBack = onBack) {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加分类")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SegmentedControl(
                selected = selectedType,
                onSelected = { selectedType = it },
                options = listOf("expense" to "支出", "income" to "收入"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Tokens.Spacing.screenHorizontal,
                        vertical = Tokens.Spacing.sm,
                    ),
            )

            val categories = if (selectedType == "expense") expenseCategories else incomeCategories

            if (categories.isEmpty()) {
                EmptyState(
                    title = "还没有${if (selectedType == "expense") "支出" else "收入"}分类",
                    subtitle = "点击右上角 + 新建一个分类，记账时就能选它了",
                    icon = Icons.Default.Category,
                    actionText = "新建分类",
                    onAction = { showAddDialog = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Tokens.Spacing.screenHorizontal,
                        end = Tokens.Spacing.screenHorizontal,
                        top = Tokens.Spacing.sm,
                        bottom = Tokens.Spacing.huge,
                    ),
                ) {
                    item(key = "group") {
                        GroupedList {
                            categories.forEach { category ->
                                CategoryRow(
                                    category = category,
                                    onClick = { editCategory = category },
                                    onDeactivate = { deleteCategory = category },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加分类弹窗
    if (showAddDialog) {
        CategoryEditDialog(
            title = "添加分类",
            initialName = "",
            initialIcon = "📁",
            initialSortOrder = 0,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, icon, sortOrder ->
                viewModel.createCategory(name, selectedType, icon, sortOrder)
                showAddDialog = false
            }
        )
    }

    // 编辑分类弹窗
    editCategory?.let { cat ->
        CategoryEditDialog(
            title = "编辑分类",
            initialName = cat.name,
            initialIcon = cat.icon,
            initialSortOrder = cat.sortOrder,
            onDismiss = { editCategory = null },
            onConfirm = { name, icon, sortOrder ->
                viewModel.updateCategory(cat.id, name, icon, sortOrder)
                editCategory = null
            }
        )
    }

    // 停用确认弹窗
    deleteCategory?.let { cat ->
        AlertDialog(
            onDismissRequest = { deleteCategory = null },
            title = { Text("停用分类") },
            text = { Text("确定停用「${cat.name}」吗？停用后不会显示在记账选项中。") },
            confirmButton = {
                AppTextButton(
                    text = "停用",
                    onClick = {
                        viewModel.deleteCategory(cat.id)
                        deleteCategory = null
                    },
                    isDestructive = true,
                )
            },
            dismissButton = {
                AppTextButton(text = "取消", onClick = { deleteCategory = null })
            }
        )
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    onClick: () -> Unit,
    onDeactivate: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    GroupedRow(
        title = category.name,
        subtitle = "排序 ${category.sortOrder}",
        onClick = onClick,
        showChevron = false,
        // 分类是 emoji 表示的，列表里必须能看到，否则用户无法快速区分
        leadingEmoji = category.icon,
        trailing = {
            Pill(
                text = if (category.type == com.aibill.android.domain.model.TransactionType.EXPENSE) "支出" else "收入",
                tone = if (category.type == com.aibill.android.domain.model.TransactionType.EXPENSE) {
                    PillTone.Expense
                } else {
                    PillTone.Income
                },
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
                        text = {
                            Text("停用", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuExpanded = false
                            onDeactivate()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun CategoryEditDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    initialSortOrder: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, sortOrder: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    var sortOrder by remember { mutableStateOf(initialSortOrder.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
                // 预览头像
                Box(modifier = Modifier.size(Tokens.Avatar.lg)) {
                    CategoryAvatar(icon = icon, modifier = Modifier.fillMaxSize())
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = it },
                    label = { Text("图标 (Emoji)") },
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sortOrder,
                    onValueChange = { sortOrder = it.filter { c -> c.isDigit() } },
                    label = { Text("排序") },
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            AppTextButton(
                text = "确定",
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, icon, sortOrder.toIntOrNull() ?: 0)
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
