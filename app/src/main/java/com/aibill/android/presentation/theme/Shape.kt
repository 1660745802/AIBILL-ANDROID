package com.aibill.android.presentation.theme


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 统一圆角规范。Material3 组件（Card/TextField 等）会默认取用这里的值。
 * 页面内尽量不要再硬编码 RoundedCornerShape，直接依赖主题或统一按钮组件。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.Radius.sm),
    small = RoundedCornerShape(Tokens.Radius.md),
    medium = RoundedCornerShape(Tokens.Radius.lg),
    large = RoundedCornerShape(Tokens.Radius.xl),
    extraLarge = RoundedCornerShape(28.dp),
)
