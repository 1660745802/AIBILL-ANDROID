package com.aibill.android.presentation.navigation

import androidx.navigation.NavHostController

/**
 * NavHostController 扩展。**所有跳转必须走这些函数**，禁止裸用 navController.navigate + popUpTo 模板。
 *
 * 命名规范：
 * - navigateToXxx()         无栈清理的标准跳转
 * - navigateToXxxClearing() 清栈后跳转（如登出、配置完成）
 * - navigateToXxxAsRoot()   重置栈底后跳转（如外部 deep link 跳首页）
 */
fun NavHostController.navigateTo(route: Route) {
    navigate(route) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

fun NavHostController.navigateToDetail(id: Int) {
    navigate(Route.TransactionDetail(id))
}

fun NavHostController.navigateToManualRecord() {
    navigate(Route.ManualRecord())
}

fun NavHostController.navigateToNotificationCenter() {
    navigate(Route.NotificationCenter)
}

fun NavHostController.navigateToSettings() {
    navigate(Route.Settings)
}

fun NavHostController.navigateToPermissionGuide() {
    navigate(Route.PermissionGuide)
}

fun NavHostController.navigateToCategoryManage() {
    navigate(Route.CategoryManage)
}

fun NavHostController.navigateToAccountManage() {
    navigate(Route.AccountManage)
}

fun NavHostController.navigateToTrash() {
    navigate(Route.Trash)
}

/** 跳首页并保留当前 Tab 状态 */
fun NavHostController.navigateToHome() {
    navigate(Route.Home) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 跳首页并清掉所有栈（用于外部 deep link） */
fun NavHostController.navigateToHomeClearing() {
    navigate(Route.Home) {
        popUpTo(Route.Home) { inclusive = true }
        launchSingleTop = true
    }
}

/** 登录成功：清栈跳首页 */
fun NavHostController.navigateToHomeAfterAuth() {
    navigate(Route.Home) {
        popUpTo(Route.Login) { inclusive = true }
        launchSingleTop = true
    }
}

/** 服务器配置完成：跳登录 */
fun NavHostController.navigateToLoginAfterServerConfig() {
    navigate(Route.Login) {
        popUpTo(Route.ServerConfig) { inclusive = true }
        launchSingleTop = true
    }
}

/** 注册成功：清栈跳首页 */
fun NavHostController.navigateToHomeAfterRegister() {
    navigate(Route.Home) {
        popUpTo(Route.Register) { inclusive = true }
        launchSingleTop = true
    }
}

/** 退出登录：清栈跳登录 */
fun NavHostController.navigateToLoginAfterLogout() {
    navigate(Route.Login) {
        popUpTo(Route.Home) { inclusive = true }
        launchSingleTop = true
    }
}

/** 401 全局处理：清空所有栈跳登录 */
fun NavHostController.navigateToLoginForce() {
    navigate(Route.Login) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** 流水页跳转（用于统计页传筛选条件） */
fun NavHostController.navigateToTransactionsFiltered(
    categoryId: Int?,
    type: String?,
    startDate: String?,
    endDate: String?,
) {
    navigate(
        Route.Transactions(
            categoryId = categoryId,
            type = type,
            startDate = startDate,
            endDate = endDate,
        )
    ) {
        popUpTo(Route.Home) { saveState = true }
        launchSingleTop = true
        restoreState = false
    }
}

/** 深链处理：来自通知的外部 Intent */
fun NavHostController.handleDeepLink(target: String?) {
    when (target) {
        "notification_center" -> navigateToNotificationCenter()
        "transactions" -> {
            navigate(Route.Transactions()) {
                popUpTo(Route.Home) { inclusive = false; saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        "manual_record" -> navigateToManualRecord()
        "home" -> navigateToHomeClearing()
        "login_force" -> navigateToLoginForce()
        else -> {}
    }
}
