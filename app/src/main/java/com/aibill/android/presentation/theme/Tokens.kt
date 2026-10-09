package com.aibill.android.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * UI 设计令牌。**所有屏幕的间距/圆角/高度必须走这里**，禁止在屏幕内硬编码 .dp。
 *
 * 间距 4 的倍数：4 / 8 / 12 / 16 / 20 / 24 / 32 / 48
 * 圆角：4 / 12 / 16 / 20 / full
 * 触控高度：40 / 48 / 52 / 56（符合 Material accessibility 48dp 下限）
 *
 * 层级约定（自上而下）：
 * - xs  (4)   元素内部紧邻间距、图标与文字
 * - sm  (8)   同组元素间距、chip 之间
 * - md  (12)  卡片内元素间距
 * - lg  (16)  卡片与卡片之间、字段之间
 * - xl  (20)  页面水平留白（= screenHorizontal）
 * - xxl (24)  大卡内边距
 * - xxxl(32)  区块之间的呼吸
 * - huge(48)  空状态纵向留白
 */
object Tokens {

    object Spacing {
        val hair = 2.dp        // 图标与文字基线微调
        val xxs = 4.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val xxxl = 32.dp
        val huge = 48.dp

        /** 页面水平安全边距（与系统手势区、屏幕圆角解耦） */
        val screenHorizontal = xl

        /** 页面水平内边距的"紧凑版"，用于全宽列表（流水/通知） */
        val screenHorizontalCompact = xl

        /** 屏幕底部 padding（FAB + BottomNavBar 不遮挡内容） */
        val screenBottomWithFab = 108.dp

        /** 独立页面（无 BottomNavBar）底部 padding */
        val screenBottom = xxxl

        /** 大卡内边距 */
        val cardPadding = xxl

        /** 小卡内边距 */
        val cardPaddingSm = lg

        /** 列表项垂直内边距 */
        val listItemVertical = md

        /** 列表项之间的间距 */
        val listItemSpacing = sm

        /** 区块标题与内容之间的间距 */
        val sectionHeaderToContent = md
    }

    object Radius {
        val none = 0.dp
        val xs = 6.dp        // 小装饰元素 / 色块
        val sm = 10.dp       // Chip / 小按钮 / 输入框
        val md = 14.dp       // 常规按钮 / 列表卡
        val lg = 18.dp       // 卡片
        val xl = 24.dp       // 汇总卡 / Hero 卡 / 底部 sheet
        val xxl = 28.dp      // 全屏 sheet
        val pill = 100.dp    // 全圆（胶囊）
    }

    object IconSize {
        val xs = 14.dp
        val sm = 18.dp
        val md = 22.dp
        val lg = 28.dp
        val xl = 36.dp
        val xxl = 48.dp
    }

    object TouchTarget {
        val small = 40.dp
        val normal = 48.dp
        val large = 52.dp    // 主按钮高度
        val xlarge = 56.dp
    }

    object Avatar {
        val sm = 28.dp
        val md = 36.dp
        val lg = 42.dp       // 交易列表图标
        val xl = 56.dp
        val hero = 64.dp
    }

    /**
     * M3 正确做法是「色调分层（tonal elevation）」而非阴影。
     * 这里保留阴影令牌只给 BottomSheet / Dialog / FAB 用，页面卡片一律用 0。
     */
    object Elevation {
        val none = 0.dp
        val low = 0.dp       // 页面卡片：靠 surface 层级区分，不投影
        val medium = 0.dp
        val sheet = 3.dp     // ModalBottomSheet
        val fab = 6.dp
        val dialog = 8.dp
    }

    object Border {
        val hairline = 0.5.dp
        val thin = 1.dp
        val thick = 2.dp
        val selected = 2.dp
    }

    /** Chip / 标签的统一度量，避免各处手写导致高度参差 */
    object Chip {
        val height = 32.dp
        val heightLarge = 36.dp
        val paddingHorizontal = Spacing.md
        val iconSize = IconSize.xs
    }

    /** 列表结构度量 */
    object List {
        /** 交易行左侧头像 + 内边距 → 分隔线的对齐基准 */
        val dividerIndent = 76.dp
        /** 日期分组 header 的高度 */
        val dateHeaderHeight = 40.dp
    }

    /** 数字键盘单键高度 */
    object Keypad {
        val keyHeight = 56.dp
        val gap = Spacing.md
    }

    /**
     * 自绘图表的固定度量。
     * Canvas 内部用 px，这些值是 Canvas 的 Modifier 尺寸，走令牌统一管理。
     */
    object Chart {
        /** 趋势图 / 环形图画布高度 */
        val canvasHeight = 160.dp

        /** 比例条 / 环形图环宽 */
        val barThickness = 6.dp

        /** 分类占比进度条高度 */
        val progressHeight = 4.dp
    }

    /**
     * 动效令牌。**只用于「回应用户动作」的动画**（按下、展开、保存成功），
     * 不用于入场轮番淡入这类装饰性动效。
     */
    object Motion {
        const val DURATION_INSTANT = 90      // 按压反馈
        const val DURATION_FAST = 150         // 颜色/尺寸变化
        const val DURATION_MEDIUM = 240       // 展开/收起、导航转场
        const val DURATION_SLOW = 380         // 强调型反馈（如保存成功）
    }
}
