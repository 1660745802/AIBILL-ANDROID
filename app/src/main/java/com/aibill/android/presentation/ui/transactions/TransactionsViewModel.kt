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
    private var lastDeletedTransaction: Transaction? = null
    private var lastDeletedIndex: Int = -1

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
            when (val result = transactionRepository.getTransactions(
                page = currentPage,
                pageSize = pageSize,
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
                    lastDeletedTransaction = transaction
                    lastDeletedIndex = index
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
        val transaction = lastDeletedTransaction ?: return
        viewModelScope.launch {
            when (val result = transactionRepository.createTransactions(listOf(transaction))) {
                is Result.Success -> {
                    // Re-insert at original position or reload
                    if (lastDeletedIndex in 0..allTransactions.size) {
                        allTransactions.add(lastDeletedIndex, transaction)
                    } else {
                        allTransactions.add(transaction)
                    }
                    val grouped = allTransactions.groupBy { it.date }
                        .toSortedMap(compareByDescending { it })
                    _uiState.update { it.copy(transactions = grouped) }
                    lastDeletedTransaction = null
                    lastDeletedIndex = -1
                }
                is Result.Error -> {
                    _uiEvent.emit(UiEvent.ShowToast("撤销失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }
}
