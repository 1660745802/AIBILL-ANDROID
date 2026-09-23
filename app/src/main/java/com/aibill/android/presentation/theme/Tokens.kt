package com.aibill.android.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * UI 设计令牌。**所有屏幕的间距/圆角/高度必须走这里**，禁止在屏幕内硬编码 .dp。
 *
 * 间距 4 的倍数：4 / 8 / 12 / 16 / 20 / 24 / 32 / 48
 * 圆角：4 / 12 / 16 / 20 / full
 * 触控高度：40 / 48 / 52 / 56（符合 Material accessibility 48dp 下限）
 */
object Tokens {
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val xxxl = 32.dp
        val huge = 48.dp

        /** 屏幕水平 padding */
        val screenHorizontal = xl

        /** 屏幕底部 padding（FAB 不遮挡） */
        val screenBottomWithFab = 100.dp

        /** 卡片内边距 */
        val cardPadding = xl

        /** 列表项垂直间距 */
        val listItemVertical = 14.dp

        /** 列表项间距 */
        val listItemSpacing = md
    }

    object Radius {
        val sm = 8.dp
        val md = 14.dp       // 按钮/输入框
        val lg = 16.dp       // 卡片
        val xl = 20.dp       // 渐变卡/汇总卡
        val pill = 100.dp    // Chip
    }

    object IconSize {
        val xs = 14.dp
        val sm = 18.dp
        val md = 22.dp
        val lg = 28.dp
        val xl = 36.dp
    }

    object TouchTarget {
        val small = 40.dp
        val normal = 48.dp
        val large = 52.dp    // 主按钮高度
        val xlarge = 56.dp
    }

    object Avatar {
        val sm = 32.dp
        val md = 40.dp
        val lg = 44.dp       // 交易列表图标
        val xl = 56.dp
    }

    object Elevation {
        val none = 0.dp
        val low = 0.5.dp     // 列表卡片轻微浮起
        val medium = 2.dp    // 普通卡片
        val high = 8.dp      // 弹窗
    }

    object Border {
        val thin = 0.5.dp
        val normal = 1.dp
        val thick = 2.dp
    }
}
