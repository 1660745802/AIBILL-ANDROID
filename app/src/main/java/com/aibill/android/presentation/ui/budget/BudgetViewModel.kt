package com.aibill.android.presentation.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.BudgetApi
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.data.remote.dto.response.CategoryDto
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetApi: BudgetApi,
    private val categoryApi: CategoryApi,
) : ViewModel() {

    data class UiState(
        val budgets: List<BudgetDto> = emptyList(),
        val categories: List<CategoryDto> = emptyList(),
        val isLoading: Boolean = false,
        val isAdding: Boolean = false,
        val error: String? = null,
        val year: Int = LocalDate.now().year,
        val month: Int = LocalDate.now().monthValue,
    ) {
        val totalAmount: Int get() = budgets.sumOf { it.amount }
        val totalSpent: Int get() = budgets.sumOf { it.spent }
        val totalProgress: Float
            get() = if (totalAmount > 0) totalSpent.toFloat() / totalAmount else 0f
    }

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class ShowError(val message: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        loadBudgets()
        loadCategories()
    }

    fun refresh() {
        loadBudgets()
    }

    fun loadBudgets(
        year: Int = _uiState.value.year,
        month: Int = _uiState.value.month,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, year = year, month = month) }
            try {
                val response = budgetApi.getBudgets(year, month)
                if (response.code == 0 && response.data != null) {
                    _uiState.update { it.copy(isLoading = false, budgets = response.data.items) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = response.message) }
                    _uiEvent.emit(UiEvent.ShowError(response.message))
                }
            } catch (e: Exception) {
                Timber.e(e, "加载预算失败")
                val msg = e.localizedMessage ?: "加载预算失败"
                _uiState.update { it.copy(isLoading = false, error = msg) }
                _uiEvent.emit(UiEvent.ShowError(msg))
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            try {
                val response = categoryApi.getCategories()
                if (response.code == 0 && response.data != null) {
                    val expenseCategories = response.data.items
                        .filter { it.type == "expense" }
                    _uiState.update { it.copy(categories = expenseCategories) }
                }
            } catch (e: Exception) {
                Timber.e(e, "加载分类失败")
            }
        }
    }

    fun onAddBudget(categoryId: Int, amount: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true) }
            try {
                val request = mapOf(
                    "category_id" to categoryId,
                    "amount" to amount,
                    "year" to _uiState.value.year,
                    "month" to _uiState.value.month,
                )
                val response = budgetApi.createBudget(request)
                if (response.code == 0) {
                    _uiEvent.emit(UiEvent.ShowToast("预算添加成功"))
                    loadBudgets()
                } else {
                    _uiEvent.emit(UiEvent.ShowError(response.message))
                }
            } catch (e: Exception) {
                Timber.e(e, "添加预算失败")
                _uiEvent.emit(UiEvent.ShowError(e.localizedMessage ?: "添加预算失败"))
            } finally {
                _uiState.update { it.copy(isAdding = false) }
            }
        }
    }

    fun onDeleteBudget(id: Int) {
        viewModelScope.launch {
            try {
                val response = budgetApi.deleteBudget(id)
                if (response.code == 0) {
                    _uiEvent.emit(UiEvent.ShowToast("删除成功"))
                    loadBudgets()
                } else {
                    _uiEvent.emit(UiEvent.ShowError(response.message))
                }
            } catch (e: Exception) {
                Timber.e(e, "删除预算失败")
                _uiEvent.emit(UiEvent.ShowError(e.localizedMessage ?: "删除失败"))
            }
        }
    }

    fun onUpdateBudget(id: Int, newAmountCents: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true) }
            try {
                val response = budgetApi.updateBudget(id, mapOf("amount" to newAmountCents))
                if (response.code == 0) {
                    _uiEvent.emit(UiEvent.ShowToast("预算已更新"))
                    loadBudgets()
                } else {
                    _uiEvent.emit(UiEvent.ShowError(response.message))
                }
            } catch (e: Exception) {
                Timber.e(e, "更新预算失败")
                _uiEvent.emit(UiEvent.ShowError(e.localizedMessage ?: "更新失败"))
            } finally {
                _uiState.update { it.copy(isAdding = false) }
            }
        }
    }
}
