package com.aibill.android.presentation.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.util.AmountUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ManualRecordViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    data class RecordUiState(
        val type: String = "expense",
        val amountText: String = "",
        val amountFen: Int = 0,
        val selectedCategoryId: Int? = null,
        val categories: List<Category> = emptyList(),
        val accountId: Int? = null,
        val targetAccountId: Int? = null,
        val description: String = "",
        val date: String = LocalDate.now().toString(),
        val isSaving: Boolean = false,
        val isExpanded: Boolean = false,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data object SaveSuccess : UiEvent()
    }

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        loadCategories(_uiState.value.type)
    }

    fun onTypeChanged(type: String) {
        _uiState.update { it.copy(type = type, selectedCategoryId = null) }
        loadCategories(type)
    }

    fun onAmountInput(char: String) {
        val current = _uiState.value.amountText
        // 限制长度防止溢出
        if (current.length >= 20) return
        // 防止连续运算符
        val operators = setOf("+", "-", "*", "/")
        if (char in operators && current.isNotEmpty() && current.last().toString() in operators) {
            return
        }
        // 小数点限制：每个数字段最多一个小数点，且小数最多2位
        if (char == ".") {
            val lastSegment = current.split(Regex("[+\\-*/]")).lastOrNull().orEmpty()
            if ("." in lastSegment) return
        }
        if (char != "." && char !in operators) {
            val lastSegment = current.split(Regex("[+\\-*/]")).lastOrNull().orEmpty()
            val dotIndex = lastSegment.indexOf('.')
            if (dotIndex >= 0 && lastSegment.length - dotIndex > 2) return
        }

        val newText = current + char
        val parsed = AmountUtils.parseExpression(newText)
        _uiState.update { it.copy(amountText = newText, amountFen = parsed ?: it.amountFen) }
    }

    fun onAmountDelete() {
        val current = _uiState.value.amountText
        if (current.isEmpty()) return
        val newText = current.dropLast(1)
        val parsed = if (newText.isEmpty()) 0 else AmountUtils.parseExpression(newText) ?: _uiState.value.amountFen
        _uiState.update { it.copy(amountText = newText, amountFen = parsed) }
    }

    fun onAmountEquals() {
        val current = _uiState.value.amountText
        val parsed = AmountUtils.parseExpression(current) ?: return
        val yuanStr = AmountUtils.fenToYuan(parsed)
        _uiState.update { it.copy(amountText = yuanStr, amountFen = parsed) }
    }

    fun onCategorySelected(id: Int) {
        _uiState.update { it.copy(selectedCategoryId = id) }
    }

    fun onDescriptionChanged(text: String) {
        _uiState.update { it.copy(description = text) }
    }

    fun onDateChanged(date: String) {
        _uiState.update { it.copy(date = date) }
    }

    fun onExpandToggle() {
        _uiState.update { it.copy(isExpanded = !it.isExpanded) }
    }

    fun onSave() {
        val state = _uiState.value
        if (state.amountFen <= 0) {
            viewModelScope.launch { _uiEvent.send(UiEvent.ShowToast("请输入金额")) }
            return
        }
        if (state.selectedCategoryId == null && state.type != "transfer") {
            viewModelScope.launch { _uiEvent.send(UiEvent.ShowToast("请选择分类")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val selectedCategory = state.categories.find { it.id == state.selectedCategoryId }
            val transaction = Transaction(
                clientId = UUID.randomUUID().toString(),
                type = TransactionType.fromValue(state.type),
                amount = state.amountFen,
                categoryId = state.selectedCategoryId,
                categoryName = selectedCategory?.name,
                categoryIcon = selectedCategory?.icon,
                accountId = state.accountId,
                targetAccountId = state.targetAccountId,
                description = state.description.ifBlank { null },
                date = state.date,
                source = TransactionSource.MANUAL,
            )

            val result = transactionRepository.createTransactions(listOf(transaction))
            when (result) {
                is Result.Success -> {
                    _uiEvent.send(UiEvent.ShowToast("记录成功"))
                    _uiEvent.send(UiEvent.SaveSuccess)
                    resetForm()
                }
                is Result.Error -> {
                    // 离线保存兜底
                    transactionRepository.createTransactionOffline(transaction)
                    _uiEvent.send(UiEvent.ShowToast("已离线保存"))
                    _uiEvent.send(UiEvent.SaveSuccess)
                    resetForm()
                }
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun resetForm() {
        val type = _uiState.value.type
        val categories = _uiState.value.categories
        _uiState.update {
            RecordUiState(
                type = type,
                categories = categories,
                date = LocalDate.now().toString(),
            )
        }
    }

    private fun loadCategories(type: String) {
        viewModelScope.launch {
            categoryRepository.observeCategories(type).collect { list ->
                _uiState.update { it.copy(categories = list) }
            }
        }
    }
}
