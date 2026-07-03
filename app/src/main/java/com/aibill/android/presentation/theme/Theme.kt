package com.aibill.android.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ============ 品牌配色 ============
// 主色：Teal 青绿（记账 App 常用，专业、清爽）
private val Teal = Color(0xFF00897B)
private val TealLight = Color(0xFF4DB6AC)
private val TealDark = Color(0xFF00695C)

// 收支语义色（全局统一，勿在页面内另行定义）
val ExpenseColor = Color(0xFFE53935)  // 支出红
val IncomeColor = Color(0xFF43A047)   // 收入绿

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00332E),
    secondary = TealDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF4A6360),
    error = ExpenseColor,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9F9),
    onBackground = Color(0xFF191C1C),
    surface = Color.White,
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F4),
    surfaceContainer = Color(0xFFEBF0EF),
    surfaceContainerHigh = Color(0xFFE5EBEA),
    surfaceContainerHighest = Color(0xFFDFE5E4),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
)

private val DarkColorScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = TealDark,
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFB1CCC8),
    error = Color(0xFFEF5350),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1413),
    onBackground = Color(0xFFDEE4E2),
    surface = Color(0xFF141A19),
    onSurface = Color(0xFFDEE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF181D1C),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B2A),
    surfaceContainerHighest = Color(0xFF313635),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4947),
)

/**
 * 全局主题。
 * 注意：不使用 Material You 动态取色（dynamicColor），
 * 统一用品牌 Teal 配色，保证所有页面/按钮视觉一致、可控。
 *
 * @param themeMode 用户在设置中选择的模式："system"（跟随系统）/ "light" / "dark"
 */
@Composable
fun AiBillTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
