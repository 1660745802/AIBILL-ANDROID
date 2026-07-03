package com.aibill.android.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand Colors
private val Teal = Color(0xFF009688)
private val TealDark = Color(0xFF00796B)
private val ExpenseRed = Color(0xFFF44336)
private val IncomeGreen = Color(0xFF4CAF50)

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    primaryContainer = Color(0xFFB2DFDB),
    secondary = TealDark,
    error = ExpenseRed,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF80CBC4),
    primaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF4DB6AC),
    error = Color(0xFFEF5350),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

@Composable
fun AiBillTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
