package com.aibill.android.presentation.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.response.TransactionDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val transactionApi: TransactionApi,
) : ViewModel() {

    data class TrashUiState(
        val isLoading: Boolean = false,
        val items: List<TransactionDto> = emptyList(),
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
            try {
                val response = transactionApi.getTrash()
                if (response.code == 0 && response.data != null) {
                    _uiState.update {
                        it.copy(isLoading = false, items = response.data.items)
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = response.message)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "加载回收站失败")
                _uiState.update {
                    it.copy(isLoading = false, error = "加载失败: ${e.message}")
                }
            }
        }
    }

    fun restoreTransaction(id: Int) {
        viewModelScope.launch {
            try {
                val response = transactionApi.restoreTransaction(id)
                if (response.code == 0) {
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.filter { it.id != id },
                            toastMessage = "已恢复"
                        )
                    }
                } else {
                    _uiState.update { it.copy(toastMessage = "恢复失败: ${response.message}") }
                }
            } catch (e: Exception) {
                Timber.e(e, "恢复交易失败")
                _uiState.update { it.copy(toastMessage = "恢复失败: ${e.message}") }
            }
        }
    }

    fun permanentDelete(id: Int) {
        viewModelScope.launch {
            try {
                val response = transactionApi.permanentDeleteTransaction(id)
                if (response.code == 0) {
                    _uiState.update { state ->
                        state.copy(
                            items = state.items.filter { it.id != id },
                            toastMessage = "已永久删除"
                        )
                    }
                } else {
                    _uiState.update { it.copy(toastMessage = "删除失败: ${response.message}") }
                }
            } catch (e: Exception) {
                Timber.e(e, "永久删除失败")
                _uiState.update { it.copy(toastMessage = "删除失败: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
