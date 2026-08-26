package com.aibill.android.presentation.ui.transactions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 流水页日期筛选逻辑测试
 *
 * 测试月份切换、自定义范围选择、label 生成等纯逻辑。
 */
class TransactionsDateFilterTest {

    // 模拟 ViewModel 中的日期计算逻辑

    private fun computeDateRange(year: Int, month: Int): Pair<String, String> {
        val ym = YearMonth.of(year, month)
        return ym.atDay(1).toString() to ym.atEndOfMonth().toString()
    }

    private fun computeLabel(startDate: String, endDate: String): String {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        val ym = YearMonth.from(start)
        val ymEnd = YearMonth.from(end)

        return when {
            ym == YearMonth.now() && ymEnd == YearMonth.now() && end == ym.atEndOfMonth() -> "本月"
            ym == YearMonth.now().minusMonths(1) && ymEnd == ym && end == ym.atEndOfMonth() -> "上月"
            else -> "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
        }
    }

    private fun onMonthChanged(currentStart: String, delta: Int): Pair<String, String> {
        val current = LocalDate.parse(currentStart)
        val newMonth = current.plusMonths(delta.toLong())
        val ym = YearMonth.from(newMonth)
        return ym.atDay(1).toString() to ym.atEndOfMonth().toString()
    }

    private fun onDateRangeSelected(startMillis: Long, endMillis: Long): Pair<String, String> {
        val start = java.time.Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val end = java.time.Instant.ofEpochMilli(endMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return start.toString() to end.toString()
    }

    // === 月份范围计算 ===

    @Test
    fun `当月范围正确`() {
        val (start, end) = computeDateRange(2026, 8)
        assertEquals("2026-08-01", start)
        assertEquals("2026-08-31", end)
    }

    @Test
    fun `2月范围正确（非闰年）`() {
        val (start, end) = computeDateRange(2026, 2)
        assertEquals("2026-02-01", start)
        assertEquals("2026-02-28", end)
    }

    @Test
    fun `12月范围正确`() {
        val (start, end) = computeDateRange(2026, 12)
        assertEquals("2026-12-01", start)
        assertEquals("2026-12-31", end)
    }

    // === 月份切换 ===

    @Test
    fun `向前切换一个月`() {
        val (start, end) = onMonthChanged("2026-08-01", -1)
        assertEquals("2026-07-01", start)
        assertEquals("2026-07-31", end)
    }

    @Test
    fun `向后切换一个月`() {
        val (start, end) = onMonthChanged("2026-08-01", 1)
        assertEquals("2026-09-01", start)
        assertEquals("2026-09-30", end)
    }

    @Test
    fun `跨年向前`() {
        val (start, end) = onMonthChanged("2026-01-01", -1)
        assertEquals("2025-12-01", start)
        assertEquals("2025-12-31", end)
    }

    @Test
    fun `跨年向后`() {
        val (start, end) = onMonthChanged("2026-12-01", 1)
        assertEquals("2027-01-01", start)
        assertEquals("2027-01-31", end)
    }

    // === 自定义日期范围 ===

    @Test
    fun `自定义范围 millis 转 date`() {
        val start = LocalDate.of(2026, 8, 5).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 8, 20).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (s, e) = onDateRangeSelected(start, end)
        assertEquals("2026-08-05", s)
        assertEquals("2026-08-20", e)
    }

    @Test
    fun `跨月自定义范围`() {
        val start = LocalDate.of(2026, 7, 25).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 8, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val (s, e) = onDateRangeSelected(start, end)
        assertEquals("2026-07-25", s)
        assertEquals("2026-08-10", e)
    }

    // === Label 生成 ===

    @Test
    fun `自定义范围 label 格式正确`() {
        val label = computeLabel("2026-08-05", "2026-08-20")
        assertEquals("8.5-8.20", label)
    }

    @Test
    fun `跨月 label`() {
        val label = computeLabel("2026-07-25", "2026-08-10")
        assertEquals("7.25-8.10", label)
    }
}
