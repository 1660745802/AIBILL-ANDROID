package com.aibill.android.presentation.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.StatsApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
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
    private val statsApi: StatsApi
) : ViewModel() {

    data class StatsSummary(
        val expense: Int = 0,
        val income: Int = 0,
        val balance: Int = 0,
        val expenseChange: Int = 0
    )

    data class CategoryStat(
        val categoryId: Int,
        val categoryName: String,
        val categoryIcon: String,
        val amount: Int,
        val percent: Double
    )

    data class TrendPoint(
        val date: String,
        val amount: Int
    )

    data class StatsUiState(
        val isLoading: Boolean = false,
        val year: Int = LocalDate.now().year,
        val month: Int = LocalDate.now().monthValue,
        val selectedTab: String = "expense",
        val summary: StatsSummary? = null,
        val categoryStats: List<CategoryStat> = emptyList(),
        val trendData: List<TrendPoint> = emptyList(),
        val error: String? = null
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

    fun onTabChanged(tab: String) {
        _uiState.update { it.copy(selectedTab = tab) }
        loadCategoryAndTrend()
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
            when (val result = safeApiCall { statsApi.getSummary(year, month) }) {
                is Result.Success -> {
                    val dto = result.data
                    _uiState.update {
                        it.copy(
                            summary = StatsSummary(
                                expense = dto.expense,
                                income = dto.income,
                                balance = dto.balance,
                                expenseChange = dto.expenseChange ?: 0
                            )
                        )
                    }
                }
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
            when (val result = safeApiCall { statsApi.getByCategory(year, month, type) }) {
                is Result.Success -> {
                    _uiState.update { s ->
                        s.copy(
                            categoryStats = result.data.items.map { dto ->
                                CategoryStat(
                                    categoryId = dto.categoryId,
                                    categoryName = dto.categoryName,
                                    categoryIcon = dto.categoryIcon,
                                    amount = dto.amount,
                                    percent = dto.percent
                                )
                            }
                        )
                    }
                }
                is Result.Error -> Timber.w("加载分类统计失败: ${result.message}")
                is Result.Loading -> Unit
            }

            // 加载趋势
            when (val result = safeApiCall { statsApi.getTrend(year, month, "daily", type) }) {
                is Result.Success -> {
                    _uiState.update { s ->
                        s.copy(
                            trendData = result.data.items.map { dto ->
                                TrendPoint(date = dto.date ?: dto.month ?: "", amount = dto.amount)
                            }
                        )
                    }
                }
                is Result.Error -> Timber.w("加载趋势失败: ${result.message}")
                is Result.Loading -> Unit
            }
        }
    }
}
