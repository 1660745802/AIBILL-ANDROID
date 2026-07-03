package com.aibill.android.presentation.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionApi: TransactionApi,
) : ViewModel() {

    private val transactionId: Int = savedStateHandle.toRoute<Route.TransactionDetail>().id

    data class UiState(
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val type: String = "expense",
        val amount: String = "",
        val categoryName: String = "",
        val categoryId: Int? = null,
        val accountName: String = "",
        val accountId: Int? = null,
        val description: String = "",
        val date: String = "",
        val time: String = "",
        val tags: String = "",
        val error: String? = null,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data object NavigateBack : UiEvent()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        loadTransaction()
    }

    fun onTypeChanged(type: String) {
        _uiState.update { it.copy(type = type) }
    }

    fun onAmountChanged(amount: String) {
        _uiState.update { it.copy(amount = amount) }
    }

    fun onDescriptionChanged(desc: String) {
        _uiState.update { it.copy(description = desc) }
    }

    fun onDateChanged(date: String) {
        _uiState.update { it.copy(date = date) }
    }

    fun onTimeChanged(time: String) {
        _uiState.update { it.copy(time = time) }
    }

    fun onTagsChanged(tags: String) {
        _uiState.update { it.copy(tags = tags) }
    }

    fun onSave() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            val amountCents = state.amount.toDoubleOrNull()?.let { (it * 100).toInt() }
            val requestBody = buildMap<String, Any> {
                put("type", state.type)
                if (amountCents != null) put("amount", amountCents)
                if (state.description.isNotBlank()) put("description", state.description)
                if (state.date.isNotBlank()) put("date", state.date)
                if (state.time.isNotBlank()) put("time", state.time)
                if (state.categoryId != null) put("category_id", state.categoryId!!)
                if (state.accountId != null) put("account_id", state.accountId!!)
                val tagsList = state.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (tagsList.isNotEmpty()) put("tags", tagsList)
            }
            when (val result = safeApiCall { transactionApi.updateTransaction(transactionId, requestBody) }) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _uiEvent.send(UiEvent.ShowToast("保存成功 ✅"))
                    _uiEvent.send(UiEvent.NavigateBack)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isSaving = false, error = result.message) }
                    _uiEvent.send(UiEvent.ShowToast("保存失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = safeApiCall { transactionApi.deleteTransaction(transactionId) }) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _uiEvent.send(UiEvent.ShowToast("已删除 🗑️"))
                    _uiEvent.send(UiEvent.NavigateBack)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isSaving = false, error = result.message) }
                    _uiEvent.send(UiEvent.ShowToast("删除失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun loadTransaction() {
        viewModelScope.launch {
            when (val result = safeApiCall { transactionApi.getTransaction(transactionId) }) {
                is Result.Success -> {
                    val dto = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            type = dto.type,
                            amount = (dto.amount / 100.0).toString(),
                            categoryName = dto.categoryName.orEmpty(),
                            categoryId = dto.categoryId,
                            accountName = dto.accountName.orEmpty(),
                            accountId = dto.accountId,
                            description = dto.description.orEmpty(),
                            date = dto.date,
                            time = dto.time.orEmpty(),
                            tags = dto.tagsList().joinToString(", "),
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                    _uiEvent.send(UiEvent.ShowToast("加载失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }
}
