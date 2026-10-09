package com.aibill.android.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = TealDark,
    onSecondary = Color.White,
    secondaryContainer = TealSecondaryContainerLight,
    onSecondaryContainer = TealOnSecondaryContainerLight,
    tertiary = TertiaryLight,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = Color.White,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = OnPrimaryDark,
    primaryContainer = TealDark,
    onPrimaryContainer = TealContainerLight,
    secondary = SecondaryDark,
    onSecondary = TealOnSecondaryContainerDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = TealOnSecondaryContainerDark,
    tertiary = TertiaryDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

/**
 * 全局主题。
 *
 * @param themeMode "system"（跟随系统）/ "light" / "dark"
 * @param dynamicColor Android 12+ 启用 Material You 动态取色（跟随系统壁纸）。
 *   **默认已改为关闭**（见 `UserPreferences.dynamicColorEnabled`）。
 *   原因：记账 App 的 `primary`（品牌青绿）与「支出红 / 收入绿」是一整套配套的
 *   语义契约。跟随壁纸取色会让 primary 变成用户壁纸里的任意颜色，出现
 *   「主按钮和支出金额同色」「选中 chip 和危险操作同色」这类语义冲突。
 *   想尝鲜的用户仍可在 设置 → 外观 里手动打开。
 */
@Composable
fun AiBillTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 语义色始终跟随明暗，**不受动态取色影响**：
    // 支出/收入的含义不能被壁纸改掉。
    val semantics = if (darkTheme) DarkSemantics else LightSemantics

    CompositionLocalProvider(LocalSemanticColors provides semantics) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * 取当前主题的明暗（供 Canvas 等拿不到 MaterialTheme 语义色的绘制逻辑使用）。
 */
@Composable
@ReadOnlyComposable
fun currentIsDark(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    return (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f
}
