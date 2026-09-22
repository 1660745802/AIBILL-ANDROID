package com.aibill.android.domain.usecase

import com.aibill.android.domain.repository.StreakRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * 连续记账业务用例
 *
 * domain 层只关心业务规则（连续天数判定 / 里程碑匹配），
 * 持久化细节（DataStore）由 [StreakRepository] 屏蔽。
 */
@Singleton
class StreakTracker @Inject constructor(
    private val streakRepository: StreakRepository,
) {

    val streakInfo: Flow<StreakInfo> = streakRepository.observeStreak()

    /**
     * 记账后调用，更新连续天数和总笔数
     * @return 更新后的 StreakInfo
     */
    suspend fun onTransactionRecorded(): StreakInfo = streakRepository.recordTransaction()

    /**
     * App 启动时调用：如果最后记账日期距今超过1天，重置连续天数
     * @return 更新后的 StreakInfo
     */
    suspend fun checkAndResetIfNeeded(): StreakInfo = streakRepository.resetIfExpired()

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