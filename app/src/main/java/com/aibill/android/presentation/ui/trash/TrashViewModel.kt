package com.aibill.android.presentation.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    // PR #61：删除 TransactionApi 直接依赖，全部走 TransactionRepository
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    data class TrashUiState(
        val isLoading: Boolean = false,
        val items: List<Transaction> = emptyList(),
        val error: String? = null,
        val toastMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    init {
        loadTrash()
    }

    fun loadTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = transactionRepository.getTrash()) {
                is Result.Success -> _uiState.update {
                    it.copy(isLoading = false, items = result.data)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun restoreTransaction(id: Int) {
        viewModelScope.launch {
            when (val result = transactionRepository.restoreTransaction(id)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(
                        items = state.items.filter { it.id != id },
                        toastMessage = "已恢复",
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(toastMessage = "恢复失败: ${result.message}")
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun permanentDelete(id: Int) {
        viewModelScope.launch {
            when (val result = transactionRepository.permanentDeleteTransaction(id)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(
                        items = state.items.filter { it.id != id },
                        toastMessage = "已永久删除",
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(toastMessage = "删除失败: ${result.message}")
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}