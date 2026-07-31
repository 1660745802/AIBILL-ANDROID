package com.aibill.android.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.repository.BudgetRepository
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.StreakInfo
import com.aibill.android.domain.usecase.StreakTracker
import android.app.Application
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
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import com.aibill.android.service.WidgetDataUpdater

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val statsRepository: StatsRepository,
    private val budgetRepository: BudgetRepository,
    private val notificationRecordDao: com.aibill.android.data.local.dao.NotificationRecordDao,
    private val streakTracker: StreakTracker,
    private val appLogger: com.aibill.android.util.AppLogger,
) : ViewModel() {

    data class HomeUiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val monthlyExpense: Int = 0,
        val monthlyIncome: Int = 0,
        val monthlyBudget: Int? = null,
        val inputText: String = "", // 保留：外部 Intent 预填用
        val todayTransactions: List<Transaction> = emptyList(),
        val pendingNotificationCount: Int = 0,
        val pendingSyncCount: Int = 0,
        val isSyncing: Boolean = false,
        val categoriesByType: Map<String, List<Category>> = emptyMap(),
        val availableTags: List<String> = emptyList(),
        /** 最近7天日支出趋势 (dayLabel, amountCents) */
        val weeklyTrend: List<Pair<String, Int>> = emptyList(),
        /** 连续记账天数 */
        val streakInfo: StreakInfo = StreakInfo(),
        val error: String? = null,
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
        get() = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    init {
        refresh()
        observePendingNotifications()
        observePendingSyncCount()
        observeCategories()
        observeStreak()
        loadAvailableTags()
    }

    private fun observeStreak() {
        viewModelScope.launch {
            streakTracker.checkAndResetIfNeeded()
            streakTracker.streakInfo.collect { info ->
                _uiState.update { it.copy(streakInfo = info) }
            }
        }
    }

    private fun loadWeeklyTrend() {
        viewModelScope.launch {
            val now = LocalDate.now()
            when (val result = statsRepository.getTrend(now.year, now.monthValue, "day", "expense")) {
                is Result.Success -> {
                    // 取最近7天
                    val last7 = result.data.takeLast(7).map { point ->
                        val dayLabel = point.date.takeLast(2) // "2026-07-28" -> "28"
                        dayLabel to point.amount
                    }
                    _uiState.update { it.copy(weeklyTrend = last7) }
                }
                else -> Unit
            }
        }
    }

    private fun observeCategories() {
        // 支出分类
        viewModelScope.launch {
            categoryRepository.observeCategories("expense").collect { list ->
                _uiState.update {
                    it.copy(categoriesByType = it.categoriesByType + ("expense" to list))
                }
            }
        }
        // 收入分类
        viewModelScope.launch {
            categoryRepository.observeCategories("income").collect { list ->
                _uiState.update {
                    it.copy(categoriesByType = it.categoriesByType + ("income" to list))
                }
            }
        }
    }

    private fun observePendingNotifications() {
        viewModelScope.launch {
            notificationRecordDao.observePendingCount().collect { count ->
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
     * 下拉刷新：重新加载月度支出 + 今日流水 + 同步分类账户
     * 月度支出和今日流水分开处理，互不影响
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            // PR #35：awaitAll 等所有子协程完成后再置 isRefreshing=false，
            // 避免下拉指示器瞬间消失（之前外层 launch{} 后续语句不被 await 子 launch）
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
                val deferred5 = async { loadMonthlyBudget() }
                awaitAll(deferred1, deferred2, deferred3, deferred4, deferred5)
                loadAvailableTags()
                loadWeeklyTrend()
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
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
                    it.copy(isLoading = false, todayTransactions = result.data.items.distinctBy { t -> t.clientId })
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

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is Result.Success -> _uiState.update { it.copy(availableTags = result.data) }
                else -> Unit
            }
        }
    }

    /**
     * 直接调用 StatsApi.getSummary 获取月度支出，避免拉取全部流水
     * 同时更新 Widget 数据
     * PR #64：失败时 emit UiEvent.ShowError 让 UI 显示 Snackbar+重试，
     * 之前只 Timber.e 日志用户完全感知不到
     */
    private suspend fun loadMonthlyExpense() {
        val now = LocalDate.now()
        when (val result = statsRepository.getSummary(now.year, now.monthValue)) {
            is Result.Success -> {
                _uiState.update { it.copy(monthlyExpense = result.data.expense, monthlyIncome = result.data.income) }
                WidgetDataUpdater.updateMonthlySummary(
                    context = application,
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

    /**
     * 加载月度总预算（categoryId == 0 表示总预算）
     * 用于首页展示"支出 / 预算 xxx 元"进度
     */
    private suspend fun loadMonthlyBudget() {
        val now = LocalDate.now()
        when (val result = budgetRepository.getBudgets(now.year, now.monthValue)) {
            is Result.Success -> {
                val totalBudget = result.data.firstOrNull { it.categoryId == 0 }
                _uiState.update { it.copy(monthlyBudget = totalBudget?.amount) }
            }
            is Result.Error -> {
                Timber.e("加载月度预算失败: ${result.message}")
                // 预算加载失败不影响主流程，静默处理
            }
            is Result.Loading -> Unit
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            launch { loadTodayTransactions() }
            launch { loadMonthlyExpense() }
            launch { loadMonthlyBudget() }
        }
    }
}
