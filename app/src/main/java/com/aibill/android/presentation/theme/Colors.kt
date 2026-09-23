package com.aibill.android.presentation.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌色 + 语义色集中定义。**任何屏幕文件都不应直接写 Color(0xFFxxxxxx)**，
 * 全部通过此文件暴露的语义令牌引用。
 *
 * Material You (Android 12+) 启用动态取色时，[ExpenseColor]/[IncomeColor] 等品牌色
 * 仍使用这里定义的值（不跟随系统），保证收支语义色全局一致。
 */

// ============ 品牌主色（teal 青绿） ============
internal val Teal = Color(0xFF00897B)
internal val TealLight = Color(0xFF4DB6AC)
internal val TealDark = Color(0xFF00695C)

// ============ 语义色（全局统一） ============

/** 支出红色。所有支出金额/图标/警示 */
val ExpenseColor = Color(0xFFE53935)

/** 收入绿色。所有收入金额/图标 */
val IncomeColor = Color(0xFF43A047)

/** 转账/中性（使用主题色而非自定义） */
val TransferColor = Color(0xFF607D8B)

/** 警告色（待同步、提示） */
val WarningColor = Color(0xFFF59E0B)

/** 成功色（已完成、正常状态） */
val SuccessColor = Color(0xFF4CAF50)

/** 危险色（破坏性操作）。当前与 ExpenseColor 区分语义 */
val DangerColor = ExpenseColor

// ============ 渐变（品牌渐变） ============

/** 主品牌渐变（横向）。用于首页/统计渐变卡 */
val BrandGradient: Brush = Brush.linearGradient(
    colors = listOf(Teal, TealLight),
)

/** 收入渐变（绿） */
val IncomeGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFF43A047), Color(0xFF66BB6A)),
)

/** 支出渐变（红） */
val ExpenseGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFFE53935), Color(0xFFEF5350)),
)

/** 警告渐变（橙） */
val WarningGradient: Brush = Brush.linearGradient(
    colors = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24)),
)

// ============ 通知隐私遮罩 ============

/** 通知隐私模式下金额显示 */
val PrivacyMask = "¥***"
