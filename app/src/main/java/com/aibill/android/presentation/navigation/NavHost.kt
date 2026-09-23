package com.aibill.android.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.aibill.android.presentation.ui.account.AccountManageScreen
import com.aibill.android.presentation.ui.auth.LoginScreen
import com.aibill.android.presentation.ui.auth.RegisterScreen
import com.aibill.android.presentation.ui.auth.ServerConfigScreen
import com.aibill.android.presentation.ui.category.CategoryManageScreen
import com.aibill.android.presentation.ui.home.HomeScreen
import com.aibill.android.presentation.ui.notification.NotificationCenterScreen
import com.aibill.android.presentation.ui.profile.ProfileScreenNavigation
import com.aibill.android.presentation.ui.profile.ProfileScreen
import com.aibill.android.presentation.ui.record.ManualRecordScreen
import com.aibill.android.presentation.ui.settings.PermissionGuideScreen
import com.aibill.android.presentation.ui.settings.SettingsScreen
import com.aibill.android.presentation.ui.statistics.StatisticsScreen
import com.aibill.android.presentation.ui.transactions.TransactionDetailScreen
import com.aibill.android.presentation.ui.transactions.TransactionsScreen
import com.aibill.android.presentation.ui.trash.TrashScreen

private val BottomBarRouteNames = listOf(
    Route.Home::class.qualifiedName,
    Route.Transactions::class.qualifiedName,
    Route.Statistics::class.qualifiedName,
    Route.Profile::class.qualifiedName,
)

@Composable
fun AiBillNavHost(
    startDestination: Route,
    navController: NavHostController = rememberNavController(),
    navigateTo: String? = null,
    onNavigationHandled: () -> Unit = {},
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = BottomBarRouteNames.any { currentRoute?.startsWith(it ?: "") == true }

    // 处理来自通知/外部 Intent 的跳转请求（一次性消费，避免解锁/重建时误跳）
    LaunchedEffect(navigateTo) {
        if (navigateTo != null) {
            navController.handleDeepLink(navigateTo)
            onNavigationHandled()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) BottomNavBar(navController)
        },
        floatingActionButton = {
            if (showBottomBar) {
                FloatingActionButton(
                    onClick = { navController.navigateToManualRecord() },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "快速记账",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 }
            },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 14 }
            },
        ) {
            // ===== 认证流程 =====
            composable<Route.ServerConfig> {
                ServerConfigScreen(
                    onConfigured = { navController.navigateToLoginAfterServerConfig() },
                )
            }
            composable<Route.Login> {
                LoginScreen(
                    onNavigateToHome = { navController.navigateToHomeAfterAuth() },
                    onNavigateToRegister = { navController.navigate(Route.Register) },
                    onNavigateToServerConfig = { navController.navigate(Route.ServerConfig) },
                )
            }
            composable<Route.Register> {
                RegisterScreen(
                    onRegisterSuccess = { navController.navigateToHomeAfterRegister() },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ===== 主 Tab =====
            composable<Route.Home> {
                HomeScreen(
                    onNavigateToNotification = { navController.navigateToNotificationCenter() },
                    onNavigateToStatistics = { navController.navigateTo(Route.Statistics) },
                    onNavigateToDetail = { id -> navController.navigateToDetail(id) },
                )
            }
            composable<Route.Transactions> { backStackEntry ->
                val route = backStackEntry.toRoute<Route.Transactions>()
                TransactionsScreen(
                    initialCategoryId = route.categoryId,
                    initialType = route.type,
                    initialStartDate = route.startDate,
                    initialEndDate = route.endDate,
                    onNavigateToDetail = { id -> navController.navigateToDetail(id) },
                )
            }
            composable<Route.Statistics> {
                StatisticsScreen(
                    onNavigateToCategoryTransactions = { categoryId, type, year, month ->
                        val ym = java.time.YearMonth.of(year, month)
                        navController.navigateToTransactionsFiltered(
                            categoryId = categoryId,
                            type = type,
                            startDate = ym.atDay(1).toString(),
                            endDate = ym.atEndOfMonth().toString(),
                        )
                    },
                )
            }
            composable<Route.Profile> {
                ProfileScreen(
                    navigation = ProfileScreenNavigation(
                        onSettings = { navController.navigateToSettings() },
                        onNotificationCenter = { navController.navigateToNotificationCenter() },
                        onPermissionGuide = { navController.navigateToPermissionGuide() },
                        onCategoryManage = { navController.navigateToCategoryManage() },
                        onAccountManage = { navController.navigateToAccountManage() },
                        onTrash = { navController.navigateToTrash() },
                        onLogout = { navController.navigateToLoginAfterLogout() },
                    ),
                )
            }

            // ===== 独立页面 =====
            composable<Route.ManualRecord> {
                ManualRecordScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable<Route.TransactionDetail> {
                TransactionDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable<Route.NotificationCenter> {
                NotificationCenterScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable<Route.Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToPermissionGuide = { navController.navigateToPermissionGuide() },
                )
            }
            composable<Route.PermissionGuide> {
                PermissionGuideScreen(onBack = { navController.popBackStack() })
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
