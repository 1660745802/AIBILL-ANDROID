package com.aibill.android.domain.a11y

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A11Y 准入的**唯一时间源**（接口 + `system()` 实现，模式照 `data/record/RecordClock.kt`）。
 *
 * gate 本身**不持有**时钟：三个入口都接收一次 [A11yMoment] 快照。这样 gate 是
 * 「输入值 → 状态 + 输出值」的纯状态机，测试直接喂值即可，无需 FakeClock。
 * platform 持注入的 [A11yClock]，在事件到达时取一次快照。
 *
 * `today` 与 `epochMillis` 同源：历史日期判据要判「今天」，若在 domain 里调
 * `LocalDate.now()` 会依赖默认时区、无法钉死跨零点/跨时区行为。
 */
interface A11yClock {
    fun now(): A11yMoment
}

/** 一次时刻快照：`epochMillis` 用于窗口比较，`today` 用于历史日期判据。 */
data class A11yMoment(
    val epochMillis: Long,
    val today: LocalDate,
)

/** 系统时钟（生产唯一实现）。 */
object SystemA11yClock : A11yClock {
    override fun now(): A11yMoment {
        val instant = Instant.now()
        // 不能用 LocalDate.ofInstant（API 34+）；atZone 是 API 26 起可用的等价写法。
        val zoned = instant.atZone(ZoneId.systemDefault())
        return A11yMoment(instant.toEpochMilli(), zoned.toLocalDate())
    }
}
