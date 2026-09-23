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

// ============ Material 3 Tonal Palette（Light） ============
// 用于 Theme.kt ColorScheme 定义。命名遵循 M3 规范：
// {Brand}{Role}{Variant}，其中 Variant = Light/Dark（来自 ColorScheme 主题）。
internal val TealContainerLight = Color(0xFFB2DFDB)
internal val TealOnContainerLight = Color(0xFF00332E)
internal val TealSecondaryContainerLight = Color(0xFFCCE8E4)
internal val TealOnSecondaryContainerLight = Color(0xFF00201C)
internal val TertiaryLight = Color(0xFF4A6360)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)
internal val BackgroundLight = Color(0xFFF7F9F9)
internal val OnBackgroundLight = Color(0xFF191C1C)
internal val OnSurfaceLight = Color(0xFF191C1C)
internal val SurfaceVariantLight = Color(0xFFDAE5E2)
internal val OnSurfaceVariantLight = Color(0xFF3F4947)
internal val SurfaceContainerLowestLight = Color.White
internal val SurfaceContainerLowLight = Color(0xFFF1F5F4)
internal val SurfaceContainerLight = Color(0xFFEBF0EF)
internal val SurfaceContainerHighLight = Color(0xFFE5EBEA)
internal val SurfaceContainerHighestLight = Color(0xFFDFE5E4)
internal val OutlineLight = Color(0xFF6F7977)
internal val OutlineVariantLight = Color(0xFFBEC9C6)

// ============ Material 3 Tonal Palette（Dark） ============
internal val OnPrimaryDark = Color(0xFF00382F)
internal val TealOnContainerDark = Color(0xFFB2DFDB)
internal val SecondaryDark = Color(0xFF80CBC4)
internal val OnSecondaryDark = Color(0xFF00201C)
internal val SecondaryContainerDark = Color(0xFF004D40)
internal val TealOnSecondaryContainerDark = Color(0xFFCCE8E4)
internal val TertiaryDark = Color(0xFFB1CCC8)
internal val ErrorDark = Color(0xFFEF5350)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)
internal val BackgroundDark = Color(0xFF0F1413)
internal val OnBackgroundDark = Color(0xFFDEE4E2)
internal val SurfaceDark = Color(0xFF141A19)
internal val OnSurfaceDark = Color(0xFFDEE4E2)
internal val SurfaceVariantDark = Color(0xFF3F4947)
internal val OnSurfaceVariantDark = Color(0xFFBEC9C6)
internal val SurfaceContainerLowestDark = Color(0xFF0A0F0E)
internal val SurfaceContainerLowDark = Color(0xFF181D1C)
internal val SurfaceContainerDark = Color(0xFF1C2120)
internal val SurfaceContainerHighDark = Color(0xFF262B2A)
internal val SurfaceContainerHighestDark = Color(0xFF313635)
internal val OutlineDark = Color(0xFF899391)
internal val OutlineVariantDark = Color(0xFF3F4947)

