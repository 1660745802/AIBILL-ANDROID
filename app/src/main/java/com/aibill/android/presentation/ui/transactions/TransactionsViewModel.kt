package com.aibill.android.presentation.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val transactionApi: com.aibill.android.data.remote.api.TransactionApi,
    private val categoryRepository: com.aibill.android.domain.repository.CategoryRepository,
) : ViewModel() {

    data class TransactionsUiState(
        val isLoading: Boolean = false,
        val transactions: Map<String, List<Transaction>> = emptyMap(),
        val hasMore: Boolean = true,
        val error: String? = null,
        val searchKeyword: String = "",
        val isRefreshing: Boolean = false,
        /** 流水类型筛选 (all/expense/income) */
        val filterType: String = "all",
        /** 按分类筛选 */
        val filterCategoryId: Int? = null,
        /** 标签筛选 */
        val filterTags: List<String> = emptyList(),
        /** 可选标签列表 */
        val availableTags: List<String> = emptyList(),
        /** 可选分类列表 */
        val categories: List<com.aibill.android.domain.model.Category> = emptyList(),
        /** 日期筛选（null表示不限） */
        val filterStartDate: String? = null,
        val filterEndDate: String? = null,
        val filterDateLabel: String = "全部",
        /** 当前筛选范围的合计（从stats API获取，准确） */
        val periodExpense: Int = 0,
        val periodIncome: Int = 0,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data object ShowDeleteUndo : UiEvent()
    }

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private var currentPage = 1
    private val pageSize = 20
    private val allTransactions = mutableListOf<Transaction>()
    private var searchJob: Job? = null
    // PR M6：undoDelete 用的最近删除记录（保留 serverId 用于 restore）
    private var lastDeletedTransaction: Transaction? = null

    init {
        loadTransactions(refresh = true)
        loadAvailableTags()
        loadCategories()
    }

    fun loadTransactions(refresh: Boolean = false) {
        if (refresh) {
            currentPage = 1
            allTransactions.clear()
            loadPeriodSummary()
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = currentPage == 1 && !it.isRefreshing,
                    isRefreshing = refresh && currentPage == 1,
                    error = null,
                )
            }

            val keyword = _uiState.value.searchKeyword.ifBlank { null }
            val typeFilter = _uiState.value.filterType.takeIf { it != "all" }
            val categoryFilter = _uiState.value.filterCategoryId
            val tagFilter = _uiState.value.filterTags.joinToString(",").ifEmpty { null }
            val state = _uiState.value
            // 有日期筛选时一次性加载完（合计准确），无日期时分页
            val hasDateFilter = state.filterStartDate != null
            val effectivePageSize = if (hasDateFilter) 9999 else pageSize
            when (val result = transactionRepository.getTransactions(
                page = currentPage,
                pageSize = effectivePageSize,
                startDate = state.filterStartDate,
                endDate = state.filterEndDate,
                type = typeFilter,
                categoryId = categoryFilter,
                keyword = keyword,
                tag = tagFilter,
            )) {
                is Result.Success -> {
                    val pageResult = result.data
                    // 去重：防后端返回重复记录导致 key 冲突崩溃
                    val existingIds = allTransactions.map { it.clientId }.toSet()
                    val newItems = pageResult.items.filter { it.clientId !in existingIds }
                    allTransactions.addAll(newItems)
                    val grouped = allTransactions.groupBy { it.date }
                        .toSortedMap(compareByDescending { it })
                    // PR #47：使用服务端 total 准确判定 hasMore (PRD §6.5.2)
                    // PR #46：失败回滚在 else 分支统一处理
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            transactions = grouped,
                            hasMore = (currentPage * pageSize) < pageResult.total,
                        )
                    }
                    loadPeriodSummary()
                }
                is Result.Error -> {
                    Timber.e("加载流水失败: ${result.message}")
                    // PR #46：loadMore 失败时回滚 currentPage，避免下次跳过缺失页
                    if (currentPage > 1) currentPage--
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = result.message,
                        )
                    }
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun loadMore() {
        if (!_uiState.value.hasMore || _uiState.value.isLoading) return
        currentPage++
        loadTransactions(refresh = false)
    }

    /**
     * Called when screen resumes (e.g., returning from detail page).
     * Silently refreshes data without showing loading indicator.
     */
    fun refreshOnResume() {
        loadTransactions(refresh = true)
        loadAvailableTags()
    }

    /** PR #27：切换类型筛选 */
    fun onMonthChanged(delta: Int) {
        val currentStart = _uiState.value.filterStartDate
        val base = if (currentStart != null) java.time.LocalDate.parse(currentStart)
            else java.time.LocalDate.now().withDayOfMonth(1)
        val newMonth = base.plusMonths(delta.toLong())
        val ym = java.time.YearMonth.from(newMonth)
        val label = if (ym == java.time.YearMonth.now()) "本月"
            else if (ym == java.time.YearMonth.now().minusMonths(1)) "上月"
            else "${ym.year}年${ym.monthValue}月"
        _uiState.update { it.copy(
            filterStartDate = ym.atDay(1).toString(),
            filterEndDate = ym.atEndOfMonth().toString(),
            filterDateLabel = label,
        ) }
        loadTransactions(refresh = true)
    }

    fun onJumpToCurrentMonth() {
        val ym = java.time.YearMonth.now()
        _uiState.update { it.copy(
            filterStartDate = ym.atDay(1).toString(),
            filterEndDate = ym.atEndOfMonth().toString(),
            filterDateLabel = "本月",
        ) }
        loadTransactions(refresh = true)
    }

    fun clearDateFilter() {
        _uiState.update { it.copy(
            filterStartDate = null,
            filterEndDate = null,
            filterDateLabel = "全部",
        ) }
        loadTransactions(refresh = true)
    }

    fun onDateRangeSelected(startMillis: Long, endMillis: Long) {
        val start = java.time.Instant.ofEpochMilli(startMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val end = java.time.Instant.ofEpochMilli(endMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val label = "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
        _uiState.update { it.copy(
            filterStartDate = start.toString(),
            filterEndDate = end.toString(),
            filterDateLabel = label,
        ) }
        loadTransactions(refresh = true)
    }

    /**
     * 从外部设置初始日期范围（统计页跳转时传入）
     */
    fun setDateRange(startDate: String?, endDate: String?) {
        if (startDate != null && endDate != null) {
            val start = java.time.LocalDate.parse(startDate)
            val ym = java.time.YearMonth.from(start)
            val label = if (ym == java.time.YearMonth.now()) "本月"
                else "${ym.year}年${ym.monthValue}月"
            _uiState.update { it.copy(
                filterStartDate = startDate,
                filterEndDate = endDate,
                filterDateLabel = label,
            ) }
        }
    }

    fun onFilterTypeChanged(type: String) {
        _uiState.update { it.copy(filterType = type) }
        loadTransactions(refresh = true)
    }

    fun setCategoryFilter(categoryId: Int?) {
        _uiState.update { it.copy(filterCategoryId = categoryId) }
        loadTransactions(refresh = true)
    }

    fun setTagFilter(tag: String?) {
        if (tag == null) {
            _uiState.update { it.copy(filterTags = emptyList()) }
        } else {
            _uiState.update {
                val current = it.filterTags
                val newTags = if (tag in current) current - tag else current + tag
                it.copy(filterTags = newTags)
            }
        }
        loadTransactions(refresh = true)
    }

    private fun loadPeriodSummary() {
        // 前端从已加载数据计算合计（不依赖后端summary接口，支持任意日期范围）
        val state = _uiState.value
        if (state.filterStartDate == null) {
            _uiState.update { it.copy(periodExpense = 0, periodIncome = 0) }
            return
        }
        val expense = allTransactions
            .filter { it.type == com.aibill.android.domain.model.TransactionType.EXPENSE }
            .sumOf { it.amount }
        val income = allTransactions
            .filter { it.type == com.aibill.android.domain.model.TransactionType.INCOME }
            .sumOf { it.amount }
        _uiState.update { it.copy(periodExpense = expense, periodIncome = income) }
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is com.aibill.android.domain.model.Result.Success -> {
                    _uiState.update { it.copy(availableTags = result.data) }
                }
                else -> Unit
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = categoryRepository.getCategoriesOnce()) {
                is Result.Success -> {
                    _uiState.update { it.copy(categories = result.data) }
                }
                else -> Unit
            }
        }
    }

    fun onSearchChanged(keyword: String) {
        _uiState.update { it.copy(searchKeyword = keyword) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300L) // debounce
            loadTransactions(refresh = true)
        }
    }

    fun onDeleteTransaction(id: Int) {
        viewModelScope.launch {
            // Save for undo
            val index = allTransactions.indexOfFirst { it.id == id }
            val transaction = if (index >= 0) allTransactions[index] else null

            when (val result = transactionRepository.deleteTransaction(id)) {
                is Result.Success -> {
                    // PR M6：保留 deleted transaction 用于 undo（restore 调用 serverId）
                    lastDeletedTransaction = transaction
                    allTransactions.removeAll { it.id == id }
                    val grouped = allTransactions.groupBy { it.date }
                        .toSortedMap(compareByDescending { it })
                    _uiState.update { it.copy(transactions = grouped) }
                    _uiEvent.emit(UiEvent.ShowDeleteUndo)
                }
                is Result.Error -> {
                    Timber.e("删除失败: ${result.message}")
                    _uiEvent.emit(UiEvent.ShowToast("删除失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun undoDelete() {
        // PR M6：undoDelete 之前用 createTransactions 复用 clientId，
        // 服务端 idempotency 把它放回 duplicates 列表，不会恢复 serverId，
        // 后续编辑/删除无法用 serverId → 走错行。
        // 改用 Repository.restoreTransaction(id) 真正恢复服务端记录。
        // 拿不到 id 的边界（旧 clientId-only 数据）降级为重建。
        val transaction = lastDeletedTransaction ?: return
        viewModelScope.launch {
            val result = if (transaction.id != null) {
                transactionRepository.restoreTransaction(transaction.id)
            } else {
                // 本地新建的还没 sync 过，无 serverId，只能重建
                transactionRepository.createTransactions(listOf(transaction))
                    .map { Unit }
            }
            when (result) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("已恢复"))
                    loadTransactions(refresh = true)
                }
                is Result.Error -> {
                    _uiEvent.emit(UiEvent.ShowToast("撤销失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }
}
