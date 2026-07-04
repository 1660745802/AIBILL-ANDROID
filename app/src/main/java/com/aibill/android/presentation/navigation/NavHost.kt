package com.aibill.android.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibill.android.presentation.ui.auth.LoginScreen
import com.aibill.android.presentation.ui.auth.RegisterScreen
import com.aibill.android.presentation.ui.auth.ServerConfigScreen
import com.aibill.android.presentation.ui.account.AccountManageScreen
import com.aibill.android.presentation.ui.budget.BudgetScreen
import com.aibill.android.presentation.ui.category.CategoryManageScreen
import com.aibill.android.presentation.ui.chat.AiChatScreen
import com.aibill.android.presentation.ui.home.HomeScreen
import com.aibill.android.presentation.ui.import_.CsvImportScreen
import com.aibill.android.presentation.ui.notification.NotificationCenterScreen
import com.aibill.android.presentation.ui.profile.ProfileScreen
import com.aibill.android.presentation.ui.record.ManualRecordScreen
import com.aibill.android.presentation.ui.recurring.RecurringScreen
import com.aibill.android.presentation.ui.settings.AutoRulesScreen
import com.aibill.android.presentation.ui.settings.PermissionGuideScreen
import com.aibill.android.presentation.ui.settings.SettingsScreen
import com.aibill.android.presentation.ui.statistics.StatisticsScreen
import com.aibill.android.presentation.ui.template.TemplateScreen
import com.aibill.android.presentation.ui.transactions.TransactionDetailScreen
import com.aibill.android.presentation.ui.transactions.TransactionsScreen
import com.aibill.android.presentation.ui.trash.TrashScreen

@Composable
fun AiBillNavHost(
    startDestination: Route,
    navController: NavHostController = rememberNavController(),
    navigateTo: String? = null,
    aiInputPrefill: String? = null,
    onNavigationHandled: () -> Unit = {},
    onAiInputConsumed: () -> Unit = {},
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // 处理来自通知的跳转请求（一次性消费，避免解锁/重建时误跳）
    androidx.compose.runtime.LaunchedEffect(navigateTo) {
        when (navigateTo) {
            "notification_center" -> navController.navigate(Route.NotificationCenter)
            "manual_record" -> navController.navigate(Route.ManualRecord())
            "home" -> {
                // 外部 Intent（Tasker/AI_PARSE）跳首页
                navController.navigate(Route.Home) {
                    popUpTo(Route.Home) { inclusive = true }
                }
            }
            "login_force" -> {
                // 401 全局处理：清栈跳登录
                navController.navigate(Route.Login) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            else -> {}
        }
        if (navigateTo != null) {
            onNavigationHandled()
        }
    }

    val showBottomBar = navBackStackEntry?.destination?.route in listOf(
        Route.Home::class.qualifiedName,
        Route.Transactions::class.qualifiedName,
        Route.Statistics::class.qualifiedName,
        Route.Profile::class.qualifiedName,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(navController = navController)
            }
        },
        floatingActionButton = {
            // PRD §5.1：FAB 快速记账入口属于主框架，4 个 Tab 都可见
            if (showBottomBar) {
                FloatingActionButton(
                    onClick = { navController.navigate(Route.ManualRecord()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "快速记账",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            // 统一轻量转场：快速淡入 + 轻微横移，避免默认 700ms 长动画卡顿
            enterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 }
            },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 14 }
            },
        ) {
            // --- 认证流程 ---
            composable<Route.ServerConfig> {
                ServerConfigScreen(
                    onConfigured = {
                        navController.navigate(Route.Login) {
                            popUpTo(Route.ServerConfig) { inclusive = true }
                        }
                    }
                )
            }
            composable<Route.Login> {
                LoginScreen(
                    onNavigateToHome = {
                        navController.navigate(Route.Home) {
                            popUpTo(Route.Login) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate(Route.Register) },
                    onNavigateToServerConfig = {
                        navController.navigate(Route.ServerConfig)
                    }
                )
            }
            composable<Route.Register> {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Route.Home) {
                            popUpTo(Route.Register) { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // --- 主 Tab 页面 ---
            composable<Route.Home> {
                HomeScreen(
                    aiInputPrefill = aiInputPrefill,
                    onAiInputConsumed = onAiInputConsumed,
                    onNavigateToManualRecord = { navController.navigate(Route.ManualRecord()) },
                    onNavigateToNotification = { navController.navigate(Route.NotificationCenter) }
                )
            }
            composable<Route.Transactions> {
                TransactionsScreen(
                    onNavigateToDetail = { id ->
                        navController.navigate(Route.TransactionDetail(id))
                    }
                )
            }
            composable<Route.Statistics> {
                StatisticsScreen()
            }
            composable<Route.Profile> {
                ProfileScreen(
                    onNavigateToAiChat = { navController.navigate(Route.AiChat) },
                    onNavigateToBudget = { navController.navigate(Route.Budget) },
                    onNavigateToSettings = { navController.navigate(Route.Settings) },
                    onNavigateToExport = { navController.navigate(Route.CsvImport) },
                    onNavigateToNotification = {
                        navController.navigate(Route.PermissionGuide)
                    },
                    onNavigateToCategoryManage = {
                        navController.navigate(Route.CategoryManage)
                    },
                    onNavigateToAccountManage = {
                        navController.navigate(Route.AccountManage)
                    },
                    onNavigateToTrash = {
                        navController.navigate(Route.Trash)
                    },
                    onNavigateToTemplate = {
                        navController.navigate(Route.Template)
                    },
                    onLogout = {
                        navController.navigate(Route.Login) {
                            popUpTo(Route.Home) { inclusive = true }
                        }
                    }
                )
            }

            // --- 独立页面 ---
            composable<Route.ManualRecord> {
                ManualRecordScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable<Route.TransactionDetail> {
                TransactionDetailScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable<Route.Template> {
                TemplateScreen(
                    onNavigateBack = { navController.popBackStack() },
                    // P1#28：传递 templateId 给 ManualRecord，触发预填
                    onNavigateToRecord = { template ->
                        navController.navigate(Route.ManualRecord(templateId = template.id))
                    }
                )
            }
            composable<Route.AiChat> {
                AiChatScreen()
            }
            composable<Route.Budget> {
                BudgetScreen()
            }
            composable<Route.NotificationCenter> {
                NotificationCenterScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable<Route.CsvImport> {
                CsvImportScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable<Route.Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToPermissionGuide = {
                        navController.navigate(Route.PermissionGuide)
                    },
                    onNavigateToRecurring = {
                        navController.navigate(Route.Recurring)
                    },
                    onNavigateToAutoRules = {
                        navController.navigate(Route.AutoRules)
                    }
                )
            }
            composable<Route.PermissionGuide> {
                PermissionGuideScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Recurring> {
                RecurringScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.AutoRules> {
                AutoRulesScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.CategoryManage> {
                CategoryManageScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.AccountManage> {
                AccountManageScreen(onBack = { navController.popBackStack() })
            }
            composable<Route.Trash> {
                TrashScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
