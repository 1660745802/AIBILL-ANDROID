package com.aibill.android.presentation.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.dto.response.BudgetDto
import com.aibill.android.data.remote.dto.response.CategoryDto
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.BudgetRepository
import com.aibill.android.domain.repository.CategoryRepository
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
    // PR #61：下沉到 Repository，删除 BudgetApi/CategoryApi 直接依赖
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
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

    private fun loadBudgets(
        year: Int = _uiState.value.year,
        month: Int = _uiState.value.month,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, year = year, month = month) }
            when (val result = budgetRepository.getBudgets(year, month)) {
                is Result.Success -> {
                    // Repository 返回 Domain Budget，转 BudgetDto 维持 UI 兼容
                    // （后续 PR 改造 BudgetComponents 改用 Budget Domain 后可移除）
                    _uiState.update {
                        it.copy(isLoading = false, budgets = result.data.map { b -> b.toDto() })
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                    _uiEvent.emit(UiEvent.ShowError(result.message))
                }
                is Result.Loading -> Unit
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = categoryRepository.getCategoriesOnce()) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(categories = result.data.filter { c -> c.type == com.aibill.android.domain.model.TransactionType.EXPENSE }
                            .map { c -> c.toDto() })
                    }
                }
                is Result.Error -> Timber.e("加载分类失败: ${result.message}")
                is Result.Loading -> Unit
            }
        }
    }

    fun onAddBudget(categoryId: Int, amount: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true) }
            when (val result = budgetRepository.createBudget(
                categoryId, amount, _uiState.value.year, _uiState.value.month,
            )) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("预算添加成功"))
                    loadBudgets()
                }
                is Result.Error -> _uiEvent.emit(UiEvent.ShowError(result.message))
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isAdding = false) }
        }
    }

    fun onDeleteBudget(id: Int) {
        viewModelScope.launch {
            when (val result = budgetRepository.deleteBudget(id)) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("删除成功"))
                    loadBudgets()
                }
                is Result.Error -> _uiEvent.emit(UiEvent.ShowError(result.message))
                is Result.Loading -> Unit
            }
        }
    }

    fun onUpdateBudget(id: Int, newAmountCents: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true) }
            when (val result = budgetRepository.updateBudget(id, newAmountCents)) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("预算已更新"))
                    loadBudgets()
                }
                is Result.Error -> _uiEvent.emit(UiEvent.ShowError(result.message))
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isAdding = false) }
        }
    }
}

private fun com.aibill.android.domain.repository.Budget.toDto(): BudgetDto = BudgetDto(
    id = id,
    categoryId = categoryId,
    categoryName = categoryName,
    amount = amount,
    spent = spent,
    year = year,
    month = month,
)

private fun Category.toDto(): CategoryDto = CategoryDto(
    id = id,
    name = name,
    type = type.value,
    icon = icon,
    sortOrder = sortOrder,
)