package com.aibill.android.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 覆盖 [StreakTracker] 业务逻辑：
 * - 首次记账 → streak=1
 * - 同日重复记账 → streak 不变（coerceAtLeast(1)）
 * - 连续日 +1
 * - 断签后重置为 1
 * - checkMilestone / checkCountMilestone 触发条件
 */
class StreakTrackerTest {

    @Test
    fun `first record sets streak to 1`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        val info = tracker.onTransactionRecorded()

        assertEquals(1, info.currentStreak)
        assertEquals(1, info.totalCount)
        assertEquals(1, info.longestStreak)
    }

    @Test
    fun `multiple records on same day do not increment streak`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        tracker.onTransactionRecorded()
        tracker.onTransactionRecorded()
        val info = tracker.onTransactionRecorded()

        assertEquals(3, info.totalCount)
        // 同日连续记账 streak 不增（保持 1）
        assertEquals(1, info.currentStreak)
    }

    @Test
    fun `streak increments on consecutive days`() = runTest {
        // 直接操作 FakeStreakRepository 模拟"昨天记过"的状态
        val repo = FakeStreakRepository()
        repo.setLastRecordDateForTest(java.time.LocalDate.now().minusDays(1).toString())
        repo.setCurrentStreakForTest(3)

        val tracker = StreakTracker(repo)
        val info = tracker.onTransactionRecorded()

        assertEquals(4, info.currentStreak)
        assertEquals(4, info.longestStreak)
    }

    @Test
    fun `streak resets to 1 after gap`() = runTest {
        val repo = FakeStreakRepository()
        // 3 天前记过，streak 是 5，longestStreak 也是 5
        repo.setLastRecordDateForTest(java.time.LocalDate.now().minusDays(3).toString())
        repo.setCurrentStreakForTest(5)
        // 直接修改 longestStreak 通过反射式 API（仅测试用）
        repo.setLongestStreakForTest(5)

        val tracker = StreakTracker(repo)
        val info = tracker.onTransactionRecorded()

        // 断签后重置为 1
        assertEquals(1, info.currentStreak)
        // longestStreak 保留历史最高
        assertEquals(5, info.longestStreak)
    }

    @Test
    fun `resetIfExpired clears streak when gap exceeds 1 day`() = runTest {
        val repo = FakeStreakRepository()
        repo.setLastRecordDateForTest(java.time.LocalDate.now().minusDays(3).toString())
        repo.setCurrentStreakForTest(5)
        repo.setLongestStreakForTest(5)

        val tracker = StreakTracker(repo)
        val info = tracker.checkAndResetIfNeeded()

        assertEquals(0, info.currentStreak)
        assertEquals(5, info.longestStreak) // 历史最高保留
    }

    @Test
    fun `resetIfExpired preserves streak when last record was yesterday`() = runTest {
        val repo = FakeStreakRepository()
        repo.setLastRecordDateForTest(java.time.LocalDate.now().minusDays(1).toString())
        repo.setCurrentStreakForTest(3)

        val tracker = StreakTracker(repo)
        val info = tracker.checkAndResetIfNeeded()

        assertEquals(3, info.currentStreak)
    }

    @Test
    fun `resetIfExpired is no-op when no last record date`() = runTest {
        val repo = FakeStreakRepository()
        // lastRecordDate 为 null 时直接 return，不抛异常
        val tracker = StreakTracker(repo)
        val info = tracker.checkAndResetIfNeeded()

        assertEquals(0, info.currentStreak)
    }

    @Test
    fun `checkMilestone returns WEEK on day 7`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        for (i in 1..7) {
            repo.setLastRecordDateForTest(java.time.LocalDate.now().minusDays((7 - i).toLong()).toString())
            repo.setCurrentStreakForTest(i)
        }

        val milestone = tracker.checkMilestone()
        assertEquals(Milestone.WEEK, milestone)
    }

    @Test
    fun `checkMilestone returns MONTH on day 30`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setCurrentStreakForTest(30)

        assertEquals(Milestone.MONTH, tracker.checkMilestone())
    }

    @Test
    fun `checkMilestone returns null on non-milestone day`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setCurrentStreakForTest(15)

        assertNull(tracker.checkMilestone())
    }

    @Test
    fun `checkCountMilestone returns 100-record celebration`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setTotalCountForTest(100)

        assertEquals("🎊 恭喜完成第100笔记账！", tracker.checkCountMilestone())
    }

    @Test
    fun `checkCountMilestone returns 500-record celebration`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setTotalCountForTest(500)

        assertEquals("🌟 500笔记账达成！", tracker.checkCountMilestone())
    }

    @Test
    fun `checkCountMilestone returns 1000-record celebration`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setTotalCountForTest(1000)

        assertEquals("👑 记账达人：1000笔！", tracker.checkCountMilestone())
    }

    @Test
    fun `checkCountMilestone returns null on non-celebration count`() = runTest {
        val repo = FakeStreakRepository()
        val tracker = StreakTracker(repo)

        repo.setTotalCountForTest(50)

        assertNull(tracker.checkCountMilestone())
    }
}
