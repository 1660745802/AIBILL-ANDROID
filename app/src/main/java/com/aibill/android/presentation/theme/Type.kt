package com.aibill.android.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 统一排版。基于 Material3 默认字号，微调字重使标题更有力、正文更清晰。
 * 不引入自定义字体族，沿用系统默认字体，保证各机型一致渲染。
 */
private val default = Typography()

val AppTypography = Typography(
    titleLarge = default.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = default.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = default.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = default.labelMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = default.bodyLarge,
    bodyMedium = default.bodyMedium,
    bodySmall = default.bodySmall.copy(lineHeight = 18.sp),
)
