package com.aibill.android.presentation.ui.statistics

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TrendPoint
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 锁住 P1-3 回归：快速切换月份时的**陈旧响应覆盖**问题。
 *
 * 修复前：loadData 的三段 `when` 各自 await 后直接 `_uiState.update{}`，
 * 不校验「这次响应还是不是当前月份」。于是 1 月的慢响应会覆盖 2 月的快响应，
 * UI 标题写着 2 月、内容却是 1 月。
 * 修复后：loadJob?.cancel() + loadToken 守卫，旧协程无法回写、也无法误关新请求的 loading。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {

    private val statsRepository: StatsRepository = mockk()
    private lateinit var viewModel: StatisticsViewModel

    private val now = LocalDate.now()

    @BeforeEach
    fun setUp() {
        // 必须用 Unconfined（等价于生产的 Dispatchers.Main.immediate）：
        // StandardTestDispatcher 会把 launch 推迟到 advanceUntilIdle，导致两次 onMonthChanged
        // 触发的两个 loadData 协程都读到**最终**的月份（12），从而**无法复现**真实竞态。
        // Unconfined 让 launch 立即执行到第一个挂起点，协程能各自读到当时的月份（11 / 12），
        // 这正是生产环境（Main.immediate）的行为。
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // 默认实现：立即返回，标记为「当月」
        coEvery { statsRepository.getSummary(any(), any()) } answers {
            Result.Success(summaryFor(firstArg<Int>()))
        }
        coEvery { statsRepository.getByCategory(any(), any(), any()) } answers {
            Result.Success(listOf(categoryFor(firstArg<Int>())))
        }
        coEvery { statsRepository.getTrend(any(), any(), any(), any()) } answers {
            Result.Success(listOf(trendFor(firstArg<Int>())))
        }
        viewModel = StatisticsViewModel(statsRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summaryFor(year: Int) = StatsSummary(
        expense = year * 100, income = year * 10, balance = year * 90,
        expenseChange = null,
    )

    private fun categoryFor(year: Int) = CategoryStat(
        categoryId = year, categoryName = "cat-$year", categoryIcon = "🍜",
        amount = year * 100, percent = 100.0,
    )

    private fun trendFor(year: Int) = TrendPoint(date = "$year-01-01", amount = year * 100)

    @Test
    fun `init loads current month`() = runTest {
        advanceUntilIdle()
        val s = viewModel.uiState.value
        assertEquals(now.year, s.year)
        assertEquals(now.monthValue, s.month)
        assertEquals((now.year * 100), s.summary?.expense)
        assertFalse(s.isLoading)
    }

    @Test
    fun `month rollover - stepping back one month never wraps forward`() = runTest {
        advanceUntilIdle()
        val before = viewModel.uiState.value
        viewModel.onMonthChanged(-1)
        advanceUntilIdle()
        val after = viewModel.uiState.value
        assertEquals(before.month - 1, after.month)
        if (before.month == 1) {
            // 1 月往前退 → 去年 12 月
            assertEquals(12, after.month)
            assertEquals(before.year - 1, after.year)
        } else {
            assertEquals(before.year, after.year)
        }
    }

    @Test
    fun `month rollover - stepping forward past december increments year`() = runTest {
        advanceUntilIdle()
        // 先退到 12 月，再前进一步，触发 12 → 1 的跨年进位
        var guard = 0
        while (viewModel.uiState.value.month != 12 && guard++ < 24) {
            viewModel.onMonthChanged(1)
            advanceUntilIdle()
        }
        val december = viewModel.uiState.value
        assertEquals(12, december.month)

        viewModel.onMonthChanged(1)
        advanceUntilIdle()
        val january = viewModel.uiState.value
        assertEquals(1, january.month)
        assertEquals(december.year + 1, january.year)
    }

    @Test
    fun `P1-3 regression - slow response for old month does NOT overwrite new month`() = runTest {
        advanceUntilIdle() // 先让 init 的加载跑完

        // 第一次点「下个月」→ 慢；第二次再点 → 快。
        // 两次点的是不同月份，所以慢请求属于「已被取代的旧月份」。
        val (slowYear, slowMonth) = shiftMonths(now.year, now.monthValue, 1)
        val (fastYear, fastMonth) = shiftMonths(now.year, now.monthValue, 2)

        // 旧月份：10s 才返回
        coEvery { statsRepository.getSummary(slowYear, slowMonth) } coAnswers {
            delay(10_000)
            Result.Success(summaryFor(1111))
        }
        coEvery { statsRepository.getByCategory(slowYear, slowMonth, any()) } coAnswers {
            delay(10_000)
            Result.Success(listOf(categoryFor(1111)))
        }
        coEvery { statsRepository.getTrend(slowYear, slowMonth, any(), any()) } coAnswers {
            delay(10_000)
            Result.Success(listOf(trendFor(1111)))
        }
        // 新月份：立即返回
        coEvery { statsRepository.getSummary(fastYear, fastMonth) } coAnswers {
            Result.Success(summaryFor(2222))
        }
        coEvery { statsRepository.getByCategory(fastYear, fastMonth, any()) } coAnswers {
            Result.Success(listOf(categoryFor(2222)))
        }
        coEvery { statsRepository.getTrend(fastYear, fastMonth, any(), any()) } coAnswers {
            Result.Success(listOf(trendFor(2222)))
        }

        viewModel.onMonthChanged(1)          // → 旧月份（慢，10s）
        viewModel.onMonthChanged(1)          // → 新月份，应取消前一个

        advanceUntilIdle()

        val s = viewModel.uiState.value
        // 核心断言：UI 停在最新月份，且内容来自最新请求，不是被旧响应污染
        assertEquals(fastMonth, s.month)
        assertEquals(fastYear, s.year)
        assertEquals(
            summaryFor(2222).expense, s.summary?.expense,
            "旧月份的慢响应不得覆盖新月份数据",
        )
        assertEquals("cat-2222", s.categoryStats.firstOrNull()?.categoryName)
        assertEquals(trendFor(2222).amount, s.trendData.firstOrNull()?.amount)
    }

    @Test
    fun `P1-3 regression - cancelled old load must not clear the new load's isLoading`() = runTest {
        advanceUntilIdle()

        val (slowYear, slowMonth) = shiftMonths(now.year, now.monthValue, 1)
        val (fastYear, fastMonth) = shiftMonths(now.year, now.monthValue, 2)

        // 旧月份：很慢，会一直挂到 advanceUntilIdle
        coEvery { statsRepository.getSummary(slowYear, slowMonth) } coAnswers {
            delay(10_000); Result.Success(summaryFor(1111))
        }
        coEvery { statsRepository.getByCategory(slowYear, slowMonth, any()) } coAnswers {
            delay(10_000); Result.Success(emptyList())
        }
        coEvery { statsRepository.getTrend(slowYear, slowMonth, any(), any()) } coAnswers {
            delay(10_000); Result.Success(emptyList())
        }
        // 新月份：50ms 后返回——即在旧请求被取消时，新请求仍在飞
        coEvery { statsRepository.getSummary(fastYear, fastMonth) } coAnswers {
            delay(50); Result.Success(summaryFor(2222))
        }
        coEvery { statsRepository.getByCategory(fastYear, fastMonth, any()) } coAnswers {
            delay(50); Result.Success(emptyList())
        }
        coEvery { statsRepository.getTrend(fastYear, fastMonth, any(), any()) } coAnswers {
            delay(50); Result.Success(emptyList())
        }

        viewModel.onMonthChanged(1)
        viewModel.onMonthChanged(1)
        advanceTimeBy(10)   // 旧请求刚被取消，新请求还在飞

        // 旧协程的 finally 若无条件写 isLoading=false，会把新请求的 loading 误关
        assertEquals(
            true, viewModel.uiState.value.isLoading,
            "新请求仍在飞时 isLoading 必须保持 true",
        )

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading, "全部完成后 isLoading 必须归位")
        assertEquals(summaryFor(2222).expense, viewModel.uiState.value.summary?.expense)
    }

    /** 按 ViewModel 相同的进位规则计算偏移 delta 个月后的 (year, month) */
    private fun shiftMonths(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val total = year * 12 + (month - 1) + delta
        return total / 12 to (total % 12 + 1)
    }
}
