package com.aibill.android.domain.usecase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aibill.android.data.local.dao.PendingTransactionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.streakDataStore: DataStore<Preferences> by preferencesDataStore(name = "streak_data")

data class StreakInfo(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalCount: Int = 0
)

enum class Milestone(val days: Int, val label: String) {
    WEEK(7, "连续记账7天 🎉"),
    MONTH(30, "连续记账30天 🏆"),
    HUNDRED(100, "连续记账100天 💯")
}

@Singleton
class StreakTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingTransactionDao: PendingTransactionDao
) {
    private val dataStore = context.streakDataStore

    private object Keys {
        val CURRENT_STREAK = intPreferencesKey("current_streak")
        val LONGEST_STREAK = intPreferencesKey("longest_streak")
        val TOTAL_COUNT = intPreferencesKey("total_count")
        val LAST_RECORD_DATE = androidx.datastore.preferences.core.stringPreferencesKey("last_record_date")
    }

    val streakInfo: Flow<StreakInfo> = dataStore.data.map { prefs ->
        StreakInfo(
            currentStreak = prefs[Keys.CURRENT_STREAK] ?: 0,
            longestStreak = prefs[Keys.LONGEST_STREAK] ?: 0,
            totalCount = prefs[Keys.TOTAL_COUNT] ?: 0
        )
    }

    /**
     * 记账后调用，更新连续天数和总笔数
     * 逻辑：
     * - 总笔数每次 +1
     * - 连续天数：同一天记多笔只算一次；昨天记过则+1；更早或首次则重置为1
     */
    suspend fun onTransactionRecorded() {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        dataStore.edit { prefs ->
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
        }
    }

    /**
     * App 启动时调用：如果最后记账日期距今超过1天，重置连续天数
     */
    suspend fun checkAndResetIfNeeded() {
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
    }

    /**
     * 判断是否达到新里程碑
     */
    suspend fun checkMilestone(): Milestone? {
        val info = streakInfo.first()
        return Milestone.entries.find { it.days == info.currentStreak }
    }

    /**
     * 判断第 N 笔交易里程碑
     */
    suspend fun checkCountMilestone(): String? {
        val info = streakInfo.first()
        return when (info.totalCount) {
            100 -> "🎊 恭喜完成第100笔记账！"
            500 -> "🌟 500笔记账达成！"
            1000 -> "👑 记账达人：1000笔！"
            else -> null
        }
    }
}
