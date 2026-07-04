package com.aibill.android.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToManualRecord: () -> Unit = {},
    onNavigateToNotification: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is HomeViewModel.UiEvent.ShowToast -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is HomeViewModel.UiEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is HomeViewModel.UiEvent.AiFallbackToManual -> {
                    // PRD §4.1：AI 解析失败时给"去手动记账"动作
                    val result = snackbarHostState.showSnackbar(
                        message = "AI 暂时无法理解，请手动记账",
                        actionLabel = "去手动记账",
                        withDismissAction = true,
                    )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        onNavigateToManualRecord()
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("AIBILL", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToNotification) {
                        if (uiState.pendingNotificationCount > 0) {
                            BadgedBox(badge = {
                                Badge { Text("${uiState.pendingNotificationCount}") }
                            }) {
                                Icon(Icons.Default.Notifications, contentDescription = "通知中心")
                            }
                        } else {
                            Icon(Icons.Default.Notifications, contentDescription = "通知中心")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToManualRecord,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "手动记账",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isLoading && uiState.todayTransactions.isEmpty() && !uiState.isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 20.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "header") {
                        MonthlyExpenseHeader(amount = uiState.monthlyExpense)
                    }

                    item(key = "ai_input") {
                        AiInputSection(
                            inputText = uiState.inputText,
                            isParsing = uiState.isParsing,
                            onInputChanged = viewModel::onInputChanged,
                            onSend = viewModel::onParseInput,
                        )
                    }

                    item(key = "quick_phrases") {
                        QuickPhraseRow(
                            onPhraseClick = { viewModel.onInputChanged(it) },
                        )
                    }

                    if (uiState.aiParseResults != null) {
                        item(key = "ai_results") {
                            AiResultsCard(
                                results = uiState.aiParseResults.orEmpty(),
                                categoriesByType = uiState.categoriesByType,
                                onConfirmItem = viewModel::onConfirmItem,
                                onConfirmAll = viewModel::onConfirmAll,
                                onDismiss = viewModel::onDismissResults,
                                onConfirmEdited = viewModel::onConfirmEditedItem,
                            )
                        }
                    }

                    item(key = "today_title") {
                        Text(
                            text = "今日流水",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    if (uiState.todayTransactions.isEmpty() && !uiState.isLoading) {
                        item(key = "empty") { EmptyTodayCard() }
                    } else {
                        items(
                            items = uiState.todayTransactions,
                            key = { it.clientId },
                        ) { transaction ->
                            TransactionItem(transaction = transaction)
                        }
                    }
                }
            }
        }
    }
}
