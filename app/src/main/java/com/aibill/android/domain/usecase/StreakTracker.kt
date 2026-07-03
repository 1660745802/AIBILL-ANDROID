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
    }

    val streakInfo: Flow<StreakInfo> = dataStore.data.map { prefs ->
        StreakInfo(
            currentStreak = prefs[Keys.CURRENT_STREAK] ?: 0,
            longestStreak = prefs[Keys.LONGEST_STREAK] ?: 0,
            totalCount = prefs[Keys.TOTAL_COUNT] ?: 0
        )
    }

    /**
     * 记账后调用，更新连续天数
     */
    suspend fun onTransactionRecorded() {
        val current = streakInfo.first()
        val newTotal = current.totalCount + 1
        val newStreak = current.currentStreak + 1
        val newLongest = maxOf(current.longestStreak, newStreak)

        dataStore.edit { prefs ->
            prefs[Keys.TOTAL_COUNT] = newTotal
            prefs[Keys.CURRENT_STREAK] = newStreak
            prefs[Keys.LONGEST_STREAK] = newLongest
        }
    }

    /**
     * 每天检查是否需要重置连续天数
     * 可在 App 启动时调用
     */
    suspend fun checkAndResetIfNeeded(lastRecordDate: String?) {
        if (lastRecordDate == null) return

        val today = LocalDate.now()
        val lastDate = runCatching {
            LocalDate.parse(lastRecordDate, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: return

        val daysBetween = today.toEpochDay() - lastDate.toEpochDay()
        if (daysBetween > 1) {
            // 超过一天没记账，重置连续天数
            dataStore.edit { prefs ->
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
