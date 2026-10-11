package com.aibill.android.data.record

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 入账时钟：落库的 `date` / `time` / `client_created_at` 全部从这里取。
 *
 * ## 为什么是「一次快照」而不是「取当前时间」
 *
 * 三个字段必须同源。原来 6 条拷贝里既有 `Date now = Date()` 一次取（弹窗两条路径），
 * 也有 `LocalDate.now()` / `LocalDateTime.now()` 各调一次（外部静默入账）——
 * 跨零点时三者会来自两个时刻。快照把这个口子关掉，同时让单测能钉死日期。
 */
interface RecordClock {

    /** 取一次时刻快照 */
    fun now(): RecordMoment
}

/**
 * 一次时刻快照。
 *
 * @property date `pending_transactions.date`，服务端契约是 ISO `YYYY-MM-DD`。
 *   **必须**由 [LocalDate.toString] 生成，不能用 `SimpleDateFormat(pattern, Locale.getDefault())`：
 *   泰语 locale 下后者写进库的是佛历年 `2569-…`、阿拉伯语 locale 下是阿拉伯数字
 *   （`2569` 与 `٢٠٢٦` 都不是 ISO 日期），服务端会拒收或错位。
 */
data class RecordMoment(
    val instant: Instant,
    val localDate: LocalDate,
    val localTime: LocalTime,
    /** 无时区的本地日期时间串，仅外部静默入账的 `client_created_at` 用它（历史口径，见意图 KDoc） */
    val localDateTime: LocalDateTime,
) {
    /** ISO `yyyy-MM-dd` */
    val date: String get() = localDate.toString()

    /** `HH:mm`（24 小时制，无时区） */
    val time: String get() = localTime.format(HH_MM)

    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** 系统时钟：与迁移前的 `LocalDate.now()` / `LocalTime.now()` / `Instant.now()` 同源同口径 */
        fun system(): RecordMoment = of(Instant.now(), ZoneId.systemDefault())

        fun of(instant: Instant, zone: ZoneId): RecordMoment {
            // ⚠️ 不能用 LocalDate.ofInstant / LocalTime.ofInstant —— 它们是 API 34 才有的，
            // 在 minSdk 26 的设备上会 NoSuchMethodError（记录路径每次入账都要过这里）。
            // atZone(zone).toLocalDate()/toLocalTime() 是 API 26 起可用的等价写法。
            // 单测跑在 JVM 上 java.time 齐全，照不出这个差异，靠 lint 的 NewApi 守着。
            val zoned = instant.atZone(zone)
            return RecordMoment(
                instant = instant,
                localDate = zoned.toLocalDate(),
                localTime = zoned.toLocalTime(),
                localDateTime = LocalDateTime.ofInstant(instant, zone),
            )
        }
    }
}
