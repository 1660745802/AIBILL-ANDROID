package com.aibill.android.domain.repository

import com.aibill.android.domain.usecase.StreakInfo
import kotlinx.coroutines.flow.Flow

/**
 * 连续记账数据仓库
 *
 * domain 层通过此接口读写「当前连续天数 / 最长连续天数 / 总笔数 / 最后记账日期」，
 * 不感知 DataStore 实现细节。
 */
interface StreakRepository {

    fun observeStreak(): Flow<StreakInfo>

    /**
     * 记账后调用：更新总笔数 + 根据 last_record_date 计算新的连续天数。
     * @return 更新后的 StreakInfo
     */
    suspend fun recordTransaction(): StreakInfo

    /**
     * App 启动时调用：如果最后记账日期距今超过 1 天，重置连续天数为 0。
     */
    suspend fun resetIfExpired(): StreakInfo
}
