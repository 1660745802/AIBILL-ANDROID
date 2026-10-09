package com.aibill.android.presentation.theme


import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * 统一圆角规范。Material3 组件（Card/TextField 等）会默认取用这里的值。
 * 页面内尽量不要再硬编码 RoundedCornerShape，直接依赖主题或统一按钮组件。
 *
 * 层级原则：**越大的容器圆角越大**，这样嵌套时不会出现"大圆角套小圆角"的错位感。
 * - 列表行 / 输入框 / 按钮 → sm~md（10~14）
 * - 卡片 → lg（18）
 * - 汇总卡 / Hero 卡 → xl（24）
 * - BottomSheet → xxl（28）
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.Radius.xs),
    small = RoundedCornerShape(Tokens.Radius.sm),
    medium = RoundedCornerShape(Tokens.Radius.md),
    large = RoundedCornerShape(Tokens.Radius.lg),
    extraLarge = RoundedCornerShape(Tokens.Radius.xxl),
)
