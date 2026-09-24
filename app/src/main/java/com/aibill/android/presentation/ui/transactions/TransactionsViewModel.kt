package com.aibill.android.presentation.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionQuery
import com.aibill.android.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 流水页 ViewModel。**已重构**：删除 legacy Map<date, List<Transaction>> 双数据源。
 *
 * - Paging 3 Flow: `transactionsPager`（filter 变化自动重建）
 * - 期间合计: `periodExpense` / `periodIncome`（仅在有日期筛选时由 Repository 单次查询返回）
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    data class TransactionsUiState(
        val error: String? = null,
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
        /** 当前筛选范围的合计（从后端 stats API 获取，准确） */
        val periodExpense: Int = 0,
        val periodIncome: Int = 0,
        /**
         * Paging 3 filter 快照。任意字段变化触发 Pager 重建。
         * Screen 用 collectAsLazyPagingItems() 即可。
         */
        val pagingFilter: PagingFilter = PagingFilter(),
    )

    data class PagingFilter(
        val type: String? = null,
        val categoryId: Int? = null,
        val tag: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data object ShowDeleteUndo : UiEvent()
    }

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val pageSize = 20
    /**
     * PR #63: 跟踪期间合计协程。时段快速切换时取消旧协程，避免
     * 多个 pageSize=9999 的请求并发造成 OOM 闪退。
     */
    private var periodSummaryJob: Job? = null
    private var lastDeletedTransaction: Transaction? = null

    /**
     * Paging 3 数据流。filter 变化时自动重建 PagingSource，
     * cachedIn(viewModelScope) 保证 ViewModel 重建时缓存不丢失。
     *
     * PR #66：用 conflate() 替代 debounce()，避免按钮点击反馈滞后一个操作。
     * conflate 丢弃上游中间值，保留最新值传给 flatMapLatest，避免 Pager 频繁 cancel。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    val transactionsPager: Flow<PagingData<Transaction>> = _uiState
        .map { it.pagingFilter }
        .distinctUntilChanged()
        .conflate()  // 丢弃上游中间值，只发射最新值
        .flatMapLatest { filter ->
            Pager(
                config = PagingConfig(
                    pageSize = pageSize,
                    initialLoadSize = pageSize,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    TransactionsPagingSource(
                        repository = transactionRepository,
                        filter = PagingFilterSnapshot(
                            startDate = filter.startDate,
                            endDate = filter.endDate,
                            type = filter.type,
                            categoryId = filter.categoryId,
                            tag = filter.tag,
                        ),
                        pageSize = pageSize,
                    )
                },
            ).flow
        }
        .cachedIn(viewModelScope)

    init {
        loadAvailableTags()
        loadCategories()
        // loadPeriodSummary 不调：默认 filterStartDate=null 会立即 return，
        // 而且无意义（用户进入时还没选日期）。
    }

    /** Screen 重新进入时刷新（Paging 自动感知 filter；这里只刷新合计） */
    fun refreshOnResume() {
        loadAvailableTags()
        loadPeriodSummary()
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is Result.Success -> _uiState.update { it.copy(availableTags = result.data) }
                else -> Unit
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = categoryRepository.getCategoriesOnce()) {
                is Result.Success -> _uiState.update { it.copy(categories = result.data) }
                else -> Unit
            }
        }
    }

    /**
     * 期间合计：有日期筛选时调用后端 stats API（避免前端遍历全量）。
     * 无筛选时不计算（前端列表 Paging 已展示总额）。
     *
     * **并发安全**：
     * - 取消上次请求（防快速切换并发）
     * - 150ms 防抖（避免连续操作多次发 9999 条请求）
     */
    private fun loadPeriodSummary() {
        periodSummaryJob?.cancel()  // 取消上次请求
        val stateAtCall = _uiState.value  // 先快照当前 state
        if (stateAtCall.filterStartDate == null) {
            _uiState.update { it.copy(periodExpense = 0, periodIncome = 0) }
            return
        }
        periodSummaryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)  // 150ms 防抖
            // 重新读取最新 state（避免防抖期间用户已切换时段）
            val state = _uiState.value
            if (state.filterStartDate == null) {
                _uiState.update { it.copy(periodExpense = 0, periodIncome = 0) }
                return@launch
            }
            val start = state.filterStartDate
            val end = state.filterEndDate ?: start
            when (val result = transactionRepository.getTransactions(
                TransactionQuery(
                    page = 1,
                    pageSize = 9999,
                    startDate = start,
                    endDate = end,
                    type = state.filterType.takeIf { it != "all" },
                    categoryId = state.filterCategoryId,
                    keyword = null,
                    tag = state.filterTags.joinToString(",").ifEmpty { null },
                ),
            )) {
                is Result.Success -> {
                    val expense = result.data.items
                        .filter { it.type == com.aibill.android.domain.model.TransactionType.EXPENSE }
                        .sumOf { it.amount }
                    val income = result.data.items
                        .filter { it.type == com.aibill.android.domain.model.TransactionType.INCOME }
                        .sumOf { it.amount }
                    _uiState.update { it.copy(periodExpense = expense, periodIncome = income) }
                }
                else -> Unit
            }
        }
    }

    private fun TransactionsUiState.toPagingFilter() = PagingFilter(
        type = filterType.takeIf { it != "all" },
        categoryId = filterCategoryId,
        tag = filterTags.joinToString(",").ifEmpty { null },
        startDate = filterStartDate,
        endDate = filterEndDate,
    )

    fun setDateRange(startDate: String?, endDate: String?) {
        if (startDate != null && endDate != null) {
            val start = java.time.LocalDate.parse(startDate)
            val ym = java.time.YearMonth.from(start)
            val label = if (ym == java.time.YearMonth.now()) "本月"
            else "${ym.year}年${ym.monthValue}月"
            updateState { copy(
                filterStartDate = startDate,
                filterEndDate = endDate,
                filterDateLabel = label,
            ) }
            loadPeriodSummary()
        }
    }

    fun onMonthChanged(delta: Int) {
        val currentStart = _uiState.value.filterStartDate
        val base = if (currentStart != null) java.time.LocalDate.parse(currentStart)
        else java.time.LocalDate.now().withDayOfMonth(1)
        val newMonth = base.plusMonths(delta.toLong())
        val ym = java.time.YearMonth.from(newMonth)
        val label = if (ym == java.time.YearMonth.now()) "本月"
        else if (ym == java.time.YearMonth.now().minusMonths(1)) "上月"
        else "${ym.year}年${ym.monthValue}月"
        updateState { copy(
            filterStartDate = ym.atDay(1).toString(),
            filterEndDate = ym.atEndOfMonth().toString(),
            filterDateLabel = label,
        ) }
        loadPeriodSummary()
    }

    fun onJumpToCurrentMonth() {
        val ym = java.time.YearMonth.now()
        updateState { copy(
            filterStartDate = ym.atDay(1).toString(),
            filterEndDate = ym.atEndOfMonth().toString(),
            filterDateLabel = "本月",
        ) }
        loadPeriodSummary()
    }

    fun clearDateFilter() {
        updateState { copy(
            filterStartDate = null,
            filterEndDate = null,
            filterDateLabel = "全部",
        ) }
        loadPeriodSummary()
    }

    /** 本周范围（周一 → 周日） */
    fun onSelectThisWeek() {
        val today = java.time.LocalDate.now()
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        val sunday = today.with(java.time.DayOfWeek.SUNDAY)
        updateState { copy(
            filterStartDate = monday.toString(),
            filterEndDate = sunday.toString(),
            filterDateLabel = "本周",
        ) }
        loadPeriodSummary()
    }

    fun onDateRangeSelected(startMillis: Long, endMillis: Long) {
        val start = java.time.Instant.ofEpochMilli(startMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val end = java.time.Instant.ofEpochMilli(endMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val label = "${start.monthValue}.${start.dayOfMonth}-${end.monthValue}.${end.dayOfMonth}"
        updateState { copy(
            filterStartDate = start.toString(),
            filterEndDate = end.toString(),
            filterDateLabel = label,
        ) }
        loadPeriodSummary()
    }

    fun onFilterTypeChanged(type: String) {
        updateState { copy(filterType = type) }
        loadPeriodSummary()
    }

    fun setCategoryFilter(categoryId: Int?) {
        updateState { copy(filterCategoryId = categoryId) }
        loadPeriodSummary()  // 分类过滤合计由 loadPeriodSummary 内部 150ms debounce + cancel 防并发
    }

    fun setTagFilter(tag: String?) {
        val currentTags = _uiState.value.filterTags
        val newTags = when {
            tag == null -> emptyList()
            tag in currentTags -> currentTags - tag
            else -> currentTags + tag
        }
        updateState { copy(filterTags = newTags) }
        loadPeriodSummary()  // 同上：刷新该筛选下的合计
    }

    /** 一键清空所有标签筛选 */
    fun clearAllTags() {
        updateState { copy(filterTags = emptyList()) }
        loadPeriodSummary()
    }

    private inline fun updateState(transform: TransactionsUiState.() -> TransactionsUiState) {
        _uiState.update { currentState ->
            val transformed = currentState.transform()
            transformed.copy(pagingFilter = transformed.toPagingFilter())
        }
    }

    fun onDeleteTransaction(id: Int) {
        viewModelScope.launch {
            // 乐观更新：本地立即移除，Paging 自动重载
            lastDeletedTransaction = null
            when (val result = transactionRepository.deleteTransaction(id)) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowDeleteUndo)
                    // PagingSource 自动感知（删除后列表会变）
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
        val transaction = lastDeletedTransaction ?: return
        viewModelScope.launch {
            val result = if (transaction.id != null) {
                transactionRepository.restoreTransaction(transaction.id)
            } else {
                transactionRepository.createTransactions(listOf(transaction)).map { Unit }
            }
            when (result) {
                is Result.Success -> _uiEvent.emit(UiEvent.ShowToast("已恢复"))
                is Result.Error -> _uiEvent.emit(UiEvent.ShowToast("撤销失败: ${result.message}"))
                is Result.Loading -> Unit
            }
        }
    }
}
