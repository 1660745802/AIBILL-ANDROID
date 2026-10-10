package com.aibill.android.presentation.ui.statistics.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 趋势图触摸命中映射。
 *
 * 这是交互层最容易写错、又最难靠肉眼验证的一段：映射偏了半个格子，
 * 用户会看到「手指按在 15 号、高亮却落在 14 号」，而图本身完全正常。
 * 所以把坐标换算抽成纯函数单独锁死。
 */
class TrendIndexMappingTest {

    private val leftPad = 44f

    /** 构造一个「绘图区宽度已知」的画布：stepX = plotW / (count - 1) */
    private fun canvasWidth(plotW: Float) = leftPad + plotW

    @Test
    @DisplayName("空数据 → -1，不选中任何点")
    fun emptyData_noSelection() {
        assertEquals(-1, trendIndexForX(x = 200f, canvasWidth = 300f, count = 0))
    }

    @Test
    @DisplayName("单点数据 → 恒为 0，任意位置点击都选中它")
    fun singlePoint_alwaysZero() {
        assertEquals(0, trendIndexForX(x = 50f, canvasWidth = 300f, count = 1))
        assertEquals(0, trendIndexForX(x = 290f, canvasWidth = 300f, count = 1))
    }

    @Test
    @DisplayName("正好点在折线节点上 → 命中该节点")
    fun exactNodeHit() {
        val count = 31
        val plotW = 256f
        val w = canvasWidth(plotW)
        val stepX = plotW / (count - 1)

        // 首、中、末三个节点
        assertEquals(0, trendIndexForX(x = leftPad, canvasWidth = w, count = count))
        assertEquals(15, trendIndexForX(x = leftPad + stepX * 15, canvasWidth = w, count = count))
        assertEquals(30, trendIndexForX(x = leftPad + stepX * 30, canvasWidth = w, count = count))
    }

    @Test
    @DisplayName("落在两个节点中间 → 取四舍五入后的最近节点")
    fun midpoint_roundsToNearest() {
        val count = 31
        val plotW = 256f
        val w = canvasWidth(plotW)
        val stepX = plotW / (count - 1)

        // 节点 15 的位置：leftPad + stepX*15
        // 手指落在它右边 0.4 格（归一化 15.4）→ 最近的仍是 15
        val justRightOf15 = leftPad + stepX * 15f + stepX * 0.4f
        assertEquals(15, trendIndexForX(x = justRightOf15, canvasWidth = w, count = count))

        // 手指落在它左边 0.6 格（归一化 14.4）→ 最近的是 14
        val justLeftOf15 = leftPad + stepX * 15f - stepX * 0.6f
        assertEquals(14, trendIndexForX(x = justLeftOf15, canvasWidth = w, count = count))
    }

    @Test
    @DisplayName("点在左侧 Y 轴留白区 → 归到 0 号，不越界")
    fun leftPadding_clampsToFirst() {
        val w = canvasWidth(256f)
        assertEquals(0, trendIndexForX(x = 0f, canvasWidth = w, count = 31))
        assertEquals(0, trendIndexForX(x = leftPad - 1f, canvasWidth = w, count = 31))
    }

    @Test
    @DisplayName("手指滑出画布右侧 → 夹到最后一个点，不抛异常")
    fun rightOverflow_clampsToLast() {
        val w = canvasWidth(256f)
        assertEquals(30, trendIndexForX(x = w + 500f, canvasWidth = w, count = 31))
    }

    @Test
    @DisplayName("全序列任意采样：命中结果恒为合法下标（不变式）")
    fun anySample_alwaysValidIndex() {
        val count = 28
        val w = canvasWidth(240f)
        var x = -50f
        while (x <= w + 50f) {
            val idx = trendIndexForX(x = x, canvasWidth = w, count = count)
            assertEquals(true, idx in 0 until count, "x=$x 得到非法下标 $idx")
            x += 3.7f
        }
    }

    @Test
    @DisplayName("映射是单调不减的：手指右移，下标只会变大不会回退")
    fun mappingIsMonotonic() {
        val count = 31
        val w = canvasWidth(256f)
        var prev = trendIndexForX(x = leftPad, canvasWidth = w, count = count)
        var x = leftPad
        while (x <= w) {
            val idx = trendIndexForX(x = x, canvasWidth = w, count = count)
            assertEquals(true, idx >= prev, "x=$x 下标从 $prev 回退到 $idx")
            prev = idx
            x += 1.3f
        }
    }

    @Test
    @DisplayName("画布过窄（stepX 退化）不产生除零/NaN 下标")
    fun degenerateWidth_noNaN() {
        val idx = trendIndexForX(x = 50f, canvasWidth = leftPad + 0.5f, count = 31)
        assertEquals(true, idx in 0 until 31)
        // 画布宽度小于左留白时（数据刚到、布局未定），不得抛异常
        val idx2 = trendIndexForX(x = 0f, canvasWidth = 10f, count = 31)
        assertEquals(true, idx2 in 0 until 31)
    }
}
