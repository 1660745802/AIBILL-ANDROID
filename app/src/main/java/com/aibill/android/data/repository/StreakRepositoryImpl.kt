package com.aibill.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibill.android.domain.repository.StreakRepository
import com.aibill.android.domain.usecase.StreakInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streakDataStore: DataStore<Preferences> by preferencesDataStore(name = "streak_data")

/**
 * 连续记账持久化（DataStore Preferences）
 *
 * 独立 DataStore 文件「streak_data」，与 UserPreferences 隔离。
 * domain 层 [com.aibill.android.domain.usecase.StreakTracker] 只通过
 * [StreakRepository] 接口读写。
 */
@Singleton
class StreakRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : StreakRepository {

    private val dataStore = context.streakDataStore

    private object Keys {
        val CURRENT_STREAK = intPreferencesKey("current_streak")
        val LONGEST_STREAK = intPreferencesKey("longest_streak")
        val TOTAL_COUNT = intPreferencesKey("total_count")
        val LAST_RECORD_DATE = stringPreferencesKey("last_record_date")
    }

    override fun observeStreak(): Flow<StreakInfo> = dataStore.data.map { prefs ->
        StreakInfo(
            currentStreak = prefs[Keys.CURRENT_STREAK] ?: 0,
            longestStreak = prefs[Keys.LONGEST_STREAK] ?: 0,
            totalCount = prefs[Keys.TOTAL_COUNT] ?: 0,
        )
    }

    override suspend fun recordTransaction(): StreakInfo {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        return dataStore.edit { prefs ->
            // 总笔数始终 +1
            prefs[Keys.TOTAL_COUNT] = (prefs[Keys.TOTAL_COUNT] ?: 0) + 1

            val lastDateStr = prefs[Keys.LAST_RECORD_DATE]
            val currentStreak = prefs[Keys.CURRENT_STREAK] ?: 0

            val newStreak = when {
                lastDateStr == null -> 1 // 首次记账
                lastDateStr == todayStr -> currentStreak.coerceAtLeast(1) // 今天已记过，连续天数不变
                else -> {
                    val lastDate = runCatching {
                        LocalDate.parse(lastDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    }.getOrNull()
                    val daysBetween = if (lastDate != null) {
                        today.toEpochDay() - lastDate.toEpochDay()
                    } else Long.MAX_VALUE
                    when (daysBetween) {
                        1L -> currentStreak + 1 // 昨天记过，连续+1
                        else -> 1 // 中断了，重置为1
                    }
                }
            }

            prefs[Keys.CURRENT_STREAK] = newStreak
            prefs[Keys.LONGEST_STREAK] = maxOf(prefs[Keys.LONGEST_STREAK] ?: 0, newStreak)
            prefs[Keys.LAST_RECORD_DATE] = todayStr
        }.let { observeStreak().first() }
    }

    override suspend fun resetIfExpired(): StreakInfo {
        val today = LocalDate.now()
        dataStore.edit { prefs ->
            val lastDateStr = prefs[Keys.LAST_RECORD_DATE] ?: return@edit
            val lastDate = runCatching {
                LocalDate.parse(lastDateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrNull() ?: return@edit
            val daysBetween = today.toEpochDay() - lastDate.toEpochDay()
            if (daysBetween > 1) {
                prefs[Keys.CURRENT_STREAK] = 0
            }
        }
        return observeStreak().first()
    }
}
