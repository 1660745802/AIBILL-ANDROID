package com.aibill.android.presentation.utils

/**
 * 金额显示工具
 * 金额以「分」为单位存储，显示时转换为元
 */

/**
 * 分 -> 显示字符串
 * 例: 3200 -> "¥32.00"
 */
fun Int.toYuanDisplay(): String = "¥${String.format("%.2f", this / 100.0)}"

/**
 * 分 -> 纯数字字符串（不含符号）
 * 例: 3200 -> "32.00"
 */
fun Int.toYuanString(): String = String.format("%.2f", this / 100.0)
