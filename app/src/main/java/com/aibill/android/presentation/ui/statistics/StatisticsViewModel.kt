package com.aibill.android.presentation.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TrendPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    // PR M8：删除 StatsApi 直接依赖，改用 StatsRepository
    private val statsRepository: StatsRepository,
) : ViewModel() {

    data class StatsUiState(
        val isLoading: Boolean = false,
        val year: Int = LocalDate.now().year,
        val month: Int = LocalDate.now().monthValue,
        val selectedTab: String = "expense",
        val summary: StatsSummary? = null,
        val categoryStats: List<CategoryStat> = emptyList(),
        val trendData: List<TrendPoint> = emptyList(),
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /**
     * 加载任务句柄。快速连点「上/下个月」时，后一次 [loadData] 会取消前一次未完成的请求。
     *
     * 不加这个的坑：loadData 里的三段 `when` 各自 await 后直接 `_uiState.update{}`，
     * **不校验这次响应还是不是当前月份**。于是「1 月的慢响应」会覆盖
     * 「2 月的快响应」，UI 上标题写着 2 月、内容却是 1 月。
     * （TransactionsViewModel.loadPeriodSummary 早就是这个模式，这里漏了。）
     */
    private var loadJob: Job? = null

    /**
     * 加载轮次令牌。快速连点「上/下个月」时，除了取消旧协程，还用它保证
     * **只有当前这一轮**能收尾（写 isLoading=false）——
     * 否则被取消的旧协程的 finally 可能在新一轮已置 isLoading=true 之后才跑，把新请求的
     * loading 误关成 false，UI 变成「数据在转圈加载但没有 spinner」。
     */
    private var loadToken = 0

    init {
        loadData()
    }

    fun onMonthChanged(delta: Int) {
        _uiState.update { state ->
            var newYear = state.year
            var newMonth = state.month + delta
            if (newMonth < 1) {
                newMonth = 12
                newYear--
            } else if (newMonth > 12) {
                newMonth = 1
                newYear++
            }
            state.copy(year = newYear, month = newMonth)
        }
        loadData()
    }

    fun onJumpToCurrentMonth() {
        val now = LocalDate.now()
        _uiState.update { it.copy(year = now.year, month = now.monthValue) }
        loadData()
    }

    fun onTabChanged(tab: String) {
        _uiState.update { it.copy(selectedTab = tab) }
        // PR #55：Tab 切换需刷新 summary 才能切换 expense/income 环比
        loadData()
    }

    fun refresh() {
        loadData()
    }

    private fun loadData() {
        // 先取消上一次未完成的加载，避免旧月份响应回写覆盖新月份
        loadJob?.cancel()
        val token = ++loadToken
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val year = state.year
                val month = state.month
                val type = state.selectedTab

                // 加载摘要
                when (val result = statsRepository.getSummary(year, month)) {
                    is Result.Success -> _uiState.update { it.copy(summary = result.data) }
                    is Result.Error -> {
                        Timber.w("加载摘要失败: ${result.message}")
                        _uiState.update { it.copy(error = result.message) }
                    }
                    is Result.Loading -> Unit
                }

                // 分类统计与趋势图并行（原实现串行，多一个 RTT）
                val categoryDeferred = async { statsRepository.getByCategory(year, month, type) }
                val trendDeferred = async { statsRepository.getTrend(year, month, "daily", type) }

                when (val result = categoryDeferred.await()) {
                    is Result.Success -> _uiState.update { it.copy(categoryStats = result.data) }
                    is Result.Error -> Timber.w("加载分类统计失败: ${result.message}")
                    is Result.Loading -> Unit
                }

                when (val result = trendDeferred.await()) {
                    is Result.Success -> _uiState.update { it.copy(trendData = result.data) }
                    is Result.Error -> Timber.w("加载趋势失败: ${result.message}")
                    is Result.Loading -> Unit
                }
            } finally {
                // 协程被取消（被下一轮取代）时也会执行，故需 token 守卫
                if (token == loadToken) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }
}
