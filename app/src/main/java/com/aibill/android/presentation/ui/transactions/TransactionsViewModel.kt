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
) : ViewModel() {

    data class TransactionsUiState(
        val isLoading: Boolean = false,
        val transactions: Map<String, List<Transaction>> = emptyMap(),
        val hasMore: Boolean = true,
        val error: String? = null,
        val searchKeyword: String = "",
        val isRefreshing: Boolean = false,
        /** PR #27：流水类型筛选 (all/expense/income)，按 PRD §5.2.2 多维度筛选 */
        val filterType: String = "all",
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
    }

    fun loadTransactions(refresh: Boolean = false) {
        if (refresh) {
            currentPage = 1
            allTransactions.clear()
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
            // PR #27：透传 type 筛选给 Repository，后端按 type 过滤
            val typeFilter = _uiState.value.filterType.takeIf { it != "all" }
            when (val result = transactionRepository.getTransactions(
                page = currentPage,
                pageSize = pageSize,
                type = typeFilter,
                keyword = keyword,
            )) {
                is Result.Success -> {
                    val pageResult = result.data
                    allTransactions.addAll(pageResult.items)
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
    }

    /** PR #27：切换类型筛选 */
    fun onFilterTypeChanged(type: String) {
        _uiState.update { it.copy(filterType = type) }
        loadTransactions(refresh = true)
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
