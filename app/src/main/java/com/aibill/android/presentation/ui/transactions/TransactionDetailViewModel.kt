package com.aibill.android.presentation.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    // PR #61：下沉到 TransactionRepository，删除 TransactionApi 直接依赖
    private val transactionRepository: TransactionRepository,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val transactionId: Int = savedStateHandle.toRoute<Route.TransactionDetail>().id

    /** 加载时的原始分类 ID，用于对比判断用户是否修改了分类 */
    private var originalCategoryId: Int? = null

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
        val categories: List<Category> = emptyList(),
        val accounts: List<com.aibill.android.domain.model.Account> = emptyList(),
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
        observeAccounts()
        viewModelScope.launch {
            // 当前 type 对应的分类列表，type 切换时重订阅
            _uiState.map { it.type }.distinctUntilChanged().collectLatest { type ->
                categoryRepository.observeCategories(type).collect { list ->
                    _uiState.update { it.copy(categories = list) }
                }
            }
        }
    }

    private fun observeAccounts() {
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { list ->
                _uiState.update { it.copy(accounts = list) }
            }
        }
    }

    fun onCategorySelected(id: Int) {
        val cat = _uiState.value.categories.firstOrNull { it.id == id }
        _uiState.update { it.copy(
            categoryId = id,
            categoryName = cat?.name ?: it.categoryName,
        ) }
    }

    fun onAccountSelected(id: Int?) {
        val acc = _uiState.value.accounts.firstOrNull { it.id == id }
        _uiState.update { it.copy(
            accountId = id,
            accountName = acc?.name ?: "",
        ) }
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
            when (val result = transactionRepository.updateTransaction(transactionId, requestBody)) {
                is Result.Success -> {
                    // 若用户改了分类，学习新规则（PRD §4.11 / §8.5）
                    val newCategoryId = state.categoryId
                    val description = state.description
                    if (newCategoryId != null &&
                        newCategoryId != originalCategoryId &&
                        description.isNotBlank()
                    ) {
                        categoryLearningEngine.learnFromCorrection(description, newCategoryId)
                    }
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
            when (val result = transactionRepository.deleteTransaction(transactionId)) {
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
            when (val result = transactionRepository.getTransaction(transactionId)) {
                is Result.Success -> {
                    val t = result.data
                    originalCategoryId = t.categoryId
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            type = t.type.value,
                            amount = (t.amount / 100.0).toString(),
                            categoryName = t.categoryName.orEmpty(),
                            categoryId = t.categoryId,
                            accountName = t.accountName.orEmpty(),
                            accountId = t.accountId,
                            description = t.description.orEmpty(),
                            date = t.date,
                            time = t.time.orEmpty(),
                            tags = t.tags.orEmpty().joinToString(", "),
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