package com.aibill.android.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * 应用导航路由定义
 * 使用 Kotlin Serialization 实现类型安全路由
 */
sealed interface Route {

    // --- 认证流程 ---
    @Serializable data object ServerConfig : Route
    @Serializable data object Login : Route
    @Serializable data object Register : Route

    // --- 主框架 (Bottom Nav) ---
    @Serializable data object Home : Route
    @Serializable data object Transactions : Route
    @Serializable data object Statistics : Route
    @Serializable data object Profile : Route

    // --- 独立页面 ---
    @Serializable data class ManualRecord(val templateId: Long? = null) : Route
    @Serializable data class TransactionDetail(val id: Int) : Route
    @Serializable data object AiChat : Route
    @Serializable data object Budget : Route
    @Serializable data object NotificationCenter : Route
    @Serializable data object CsvImport : Route
    @Serializable data object Settings : Route
    @Serializable data object PermissionGuide : Route
    @Serializable data object Recurring : Route
    @Serializable data object Template : Route
    @Serializable data object AutoRules : Route
    @Serializable data object CategoryManage : Route
    @Serializable data object AccountManage : Route
    @Serializable data object Trash : Route
}
