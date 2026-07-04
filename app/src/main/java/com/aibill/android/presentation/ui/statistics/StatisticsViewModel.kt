package com.aibill.android.presentation.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.CategoryStat
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StatsSummary
import com.aibill.android.domain.repository.TrendPoint
import dagger.hilt.android.lifecycle.HiltViewModel
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val state = _uiState.value
            val year = state.year
            val month = state.month

            // 加载摘要
            when (val result = statsRepository.getSummary(year, month)) {
                is Result.Success -> _uiState.update { it.copy(summary = result.data) }
                is Result.Error -> {
                    Timber.w("加载摘要失败: ${result.message}")
                    _uiState.update { it.copy(error = result.message) }
                }
                is Result.Loading -> Unit
            }

            loadCategoryAndTrend()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun loadCategoryAndTrend() {
        viewModelScope.launch {
            val state = _uiState.value
            val year = state.year
            val month = state.month
            val type = state.selectedTab

            // 加载分类统计
            when (val result = statsRepository.getByCategory(year, month, type)) {
                is Result.Success -> _uiState.update {
                    it.copy(categoryStats = result.data)
                }
                is Result.Error -> Timber.w("加载分类统计失败: ${result.message}")
                is Result.Loading -> Unit
            }

            // 加载趋势
            when (val result = statsRepository.getTrend(year, month, "daily", type)) {
                is Result.Success -> _uiState.update {
                    it.copy(trendData = result.data)
                }
                is Result.Error -> Timber.w("加载趋势失败: ${result.message}")
                is Result.Loading -> Unit
            }
        }
    }
}