package com.aibill.android.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.NotificationRecordRepository
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/**
 * 首页 ViewModel。
 *
 * 设计要点：
 * - 不持有 Application/Context。Widget 更新走 [com.aibill.android.service.WidgetDataUpdater] 静态入口
 *   时，依赖通过 Hilt 注入到上层协程作用域（避免 Application 注入）
 * - 不直接注入 DAO/Api。所有数据访问走 Repository 接口
 * - 删除 [loadWeeklyTrend]：MiniTrendChart 组件已删除
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val statsRepository: StatsRepository,
    private val notificationRepository: NotificationRecordRepository,
    private val widgetSyncScheduler: com.aibill.android.service.WidgetSyncScheduler,
) : ViewModel() {

    data class HomeUiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val monthlyExpense: Int = 0,
        val monthlyIncome: Int = 0,
        val todayTransactions: List<Transaction> = emptyList(),
        val pendingNotificationCount: Int = 0,
        val pendingSyncCount: Int = 0,
        val isSyncing: Boolean = false,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class ShowError(val message: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val today: String
        get() = LocalDate.now().toString()

    init {
        refresh()
        observePendingNotifications()
        observePendingSyncCount()
    }

    private fun observePendingNotifications() {
        viewModelScope.launch {
            notificationRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingNotificationCount = count) }
            }
        }
    }

    private fun observePendingSyncCount() {
        viewModelScope.launch {
            transactionRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingSyncCount = count) }
            }
        }
    }

    fun triggerSync() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            when (val result = transactionRepository.syncPending()) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("同步完成"))
                    refreshData()
                }
                is Result.Error -> {
                    _uiEvent.emit(UiEvent.ShowError("同步失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    /**
     * 下拉刷新：分类/账户/今日流水/月度合计 4 个并行任务
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val deferred1 = async { categoryRepository.syncCategories() }
                val deferred2 = async { accountRepository.syncAccounts() }
                val deferred3 = async { loadMonthlyExpense() }
                val deferred4 = async {
                    val success = loadTodayTransactions()
                    if (!success) {
                        _uiEvent.emit(UiEvent.ShowError("加载今日流水失败，请检查网络"))
                    }
                }
                awaitAll(deferred1, deferred2, deferred3, deferred4)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun loadTodayTransactions(): Boolean {
        _uiState.update { it.copy(isLoading = true) }
        return when (val result = transactionRepository.getTransactions(
            page = 1,
            pageSize = 50,
            startDate = today,
            endDate = today,
        )) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        todayTransactions = result.data.items.distinctBy { t -> t.clientId },
                    )
                }
                true
            }
            is Result.Error -> {
                _uiState.update { it.copy(isLoading = false) }
                Timber.e("加载今日流水失败: ${result.message}")
                false
            }
            is Result.Loading -> true
        }
    }

    /**
     * 月度统计 + Widget 更新。Widget 更新通过 Repository 接口代理，避免 ViewModel 持 Context。
     */
    private suspend fun loadMonthlyExpense() {
        val now = LocalDate.now()
        when (val result = statsRepository.getSummary(now.year, now.monthValue)) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(monthlyExpense = result.data.expense, monthlyIncome = result.data.income)
                }
                // Widget 更新：通过注入的 Scheduler（不持 Context）
                widgetSyncScheduler.scheduleMonthlyUpdate(
                    expenseCents = result.data.expense,
                    incomeCents = result.data.income,
                )
            }
            is Result.Error -> {
                Timber.e("加载月度支出失败: ${result.message}")
                _uiEvent.emit(UiEvent.ShowError("加载月度数据失败: ${result.message}"))
            }
            is Result.Loading -> Unit
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            launch { loadTodayTransactions() }
            launch { loadMonthlyExpense() }
        }
    }
}
