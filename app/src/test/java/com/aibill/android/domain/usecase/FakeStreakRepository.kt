package com.aibill.android.domain.usecase

import com.aibill.android.domain.repository.StreakRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内存版 [StreakRepository]，用于单测 [StreakTracker] 业务逻辑。
 * 复用 DataStore 会涉及 Android Context，单测里走 fake 更合适。
 */
class FakeStreakRepository : StreakRepository {
    private var currentStreak = 0
    private var longestStreak = 0
    private var totalCount = 0
    private var lastRecordDate: String? = null
    private val state = MutableStateFlow(StreakInfo())

    override fun observeStreak(): Flow<StreakInfo> = state.asStateFlow()

    override suspend fun recordTransaction(): StreakInfo {
        val today = java.time.LocalDate.now().toString()
        totalCount += 1

        val newStreak = when (val lastDate = lastRecordDate) {
            null -> 1
            today -> currentStreak.coerceAtLeast(1)
            else -> {
                val last = java.time.LocalDate.parse(lastDate)
                val days = java.time.LocalDate.now().toEpochDay() - last.toEpochDay()
                when (days) {
                    1L -> currentStreak + 1
                    else -> 1
                }
            }
        }

        currentStreak = newStreak
        if (newStreak > longestStreak) longestStreak = newStreak
        lastRecordDate = today

        val info = StreakInfo(currentStreak, longestStreak, totalCount)
        state.value = info
        return info
    }

    override suspend fun resetIfExpired(): StreakInfo {
        val today = java.time.LocalDate.now()
        val lastDate = lastRecordDate?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        }
        if (lastDate != null && (today.toEpochDay() - lastDate.toEpochDay()) > 1) {
            currentStreak = 0
        }
        val info = StreakInfo(currentStreak, longestStreak, totalCount)
        state.value = info
        return info
    }

    // --- Test helpers ---

    fun setLastRecordDateForTest(date: String?) {
        lastRecordDate = date
    }

    fun setCurrentStreakForTest(value: Int) {
        currentStreak = value
        state.value = StreakInfo(currentStreak, longestStreak, totalCount)
    }

    fun setLongestStreakForTest(value: Int) {
        longestStreak = value
        state.value = StreakInfo(currentStreak, longestStreak, totalCount)
    }

    fun setTotalCountForTest(value: Int) {
        totalCount = value
        state.value = StreakInfo(currentStreak, longestStreak, totalCount)
    }
}
