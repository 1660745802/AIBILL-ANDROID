package com.aibill.android.util

import com.aibill.android.data.local.dao.AppLogDao
import com.aibill.android.data.local.entity.AppLogEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

/**
 * [AppLogger] 导出时间戳的落盘格式测试。
 *
 * 回归目标：旧实现用 `SimpleDateFormat(pattern, Locale.getDefault())`，
 * 泰语 locale 会写出佛历年（2568 而非 2025）、阿拉伯语 locale 写出非 ASCII 数字——
 * 导出的日志是给开发者排查用的落盘数据，必须是稳定的公历 ASCII 数字
 * （ARCHITECTURE §2.3 / CONTRIBUTING §3）。
 */
class AppLoggerTest {

    private lateinit var dao: AppLogDao
    private lateinit var logger: AppLogger
    private lateinit var originalLocale: Locale

    /** 2025-06-15T12:00:00Z：选正午 UTC，任何时区下公历年都仍是 2025。 */
    private val fixedMillis = Instant.parse("2025-06-15T12:00:00Z").toEpochMilli()

    @BeforeEach
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("th-TH")) // 佛历 locale
        dao = mockk()
        logger = AppLogger(dao)
    }

    @AfterEach
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    @DisplayName("泰语 locale 下导出时间戳仍是公历 ASCII 数字，不写成佛历年")
    fun `thai locale export keeps gregorian ascii year`() = runTest {
        coEvery { dao.getRecent() } returns listOf(
            AppLogEntity(
                id = 1,
                timestamp = fixedMillis,
                level = "INFO",
                tag = "NLS",
                message = "通知到达",
            ),
        )

        val text = logger.exportAsText()

        // 导出时间行必须是公历 ASCII 数字；泰语 locale 下旧实现会写成佛历年（公历+543）。
        val gregorianYear = java.time.LocalDate.now().year
        val buddhistYear = gregorianYear + 543
        assertTrue(
            text.contains("导出时间: $gregorianYear-"),
            "导出时间应为公历年 $gregorianYear：\n$text",
        )
        assertFalse(
            text.contains("导出时间: $buddhistYear-"),
            "不得写出佛历年 $buddhistYear：\n$text",
        )
        // 行内时间戳（MM-dd HH:mm:ss）不含年份，但必须存在且为 ASCII 数字。
        assertTrue(
            Regex("\\[\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}] \\[INFO] \\[NLS] 通知到达")
                .containsMatchIn(text),
            "行时间戳应为 ASCII 数字：\n$text",
        )
    }
}
