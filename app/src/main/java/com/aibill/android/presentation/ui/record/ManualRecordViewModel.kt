package com.aibill.android.presentation.ui.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.presentation.navigation.Route
import com.aibill.android.util.AmountUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val accountRepository: com.aibill.android.domain.repository.AccountRepository,
    private val templateRepository: com.aibill.android.domain.repository.TemplateRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class RecordUiState(
        val type: String = "expense",
        val amountText: String = "",
        val amountFen: Int = 0,
        val selectedCategoryId: Int? = null,
        val categories: List<Category> = emptyList(),
        val accounts: List<com.aibill.android.domain.model.Account> = emptyList(),
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

    /** PR #28：从路由参数读取的 templateId，非空则预填表单 */
    private val templateId: Long? =
        savedStateHandle.toRoute<com.aibill.android.presentation.navigation.Route.ManualRecord>().templateId

    /**
     * 当前 type 对应的分类订阅 Job，PR #36：切类型时取消旧 Job，
     * 避免 collect 协程叠加浪费 Room 订阅。
     */
    private var categoriesJob: kotlinx.coroutines.Job? = null

    init {
        loadCategories(_uiState.value.type)
        loadAccounts()
        // PR #28：如果从 TemplateScreen 跳过来，预填表单
        if (templateId != null) {
            applyTemplatePrefill(templateId)
        }
    }

    /**
     * PR #28：按 templateId 加载模板并预填表单字段。
     * 加载完成后会触发 onTypeChanged 重订阅分类列表，
     * 之后用户在已选分类上确认即可保存。
     */
    private fun applyTemplatePrefill(id: Long) {
        viewModelScope.launch {
            val template = templateRepository.findById(id) ?: return@launch
            // PR H1：先写不依赖分类列表的字段（type/amount/description/accountId）
            // 不立即设 selectedCategoryId，等 onTypeChanged 触发的分类列表加载完
            // 后再设，否则 onTypeChanged 会把它清掉。
            _uiState.update {
                it.copy(
                    type = template.type.value,
                    amountFen = template.amount,
                    amountText = "%.2f".format(template.amount / 100.0),
                    accountId = template.accountId,
                    description = template.description.orEmpty(),
                )
            }
            // 切换 type 让分类/账户列表按模板的类型加载
            onTypeChanged(template.type.value)
            // 在分类列表加载完后查模板的 categoryId 是否仍在；若分类被删则保持 null
            val templateCategoryId = template.categoryId
            if (templateCategoryId != null) {
                val currentCategories = categoryRepository
                    .observeCategories(template.type.value)
                    .first()
                val matched = currentCategories.firstOrNull { it.id == templateCategoryId }
                if (matched != null) {
                    _uiState.update { it.copy(selectedCategoryId = matched.id) }
                }
            }
            // 选择模板指定的账户
            template.accountId?.let { onAccountSelected(it) }
        }
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
    }

    fun onAccountSelected(id: Int) {
        _uiState.update { it.copy(accountId = id) }
    }

    fun onTargetAccountSelected(id: Int) {
        _uiState.update { it.copy(targetAccountId = id) }
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
        val parsed = AmountUtils.parseExpression(current)
        if (parsed == null) {
            // PR #30：解析失败给提示（PR §11.5 反馈底线）
            viewModelScope.launch { _uiEvent.send(UiEvent.ShowToast("表达式无效")) }
            return
        }
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
                type = TransactionType.fromValue(state.type) ?: TransactionType.EXPENSE,
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
                    // PR #29：仅网络错误（ERROR_NETWORK）才落离线，业务错误（422/5001 等）弹 Snackbar 让用户修正
                    if (result.code == Result.ERROR_NETWORK) {
                        transactionRepository.createTransactionOffline(transaction)
                        _uiEvent.send(UiEvent.ShowToast("已离线保存"))
                        _uiEvent.send(UiEvent.SaveSuccess)
                        resetForm()
                    } else {
                        _uiEvent.send(UiEvent.ShowToast("保存失败: ${result.message}"))
                    }
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
        // PR #36：先取消旧订阅 Job，再启动新的（避免 collect 协程叠加）
        categoriesJob?.cancel()
        categoriesJob = viewModelScope.launch {
            categoryRepository.observeCategories(type).collect { list ->
                _uiState.update { it.copy(categories = list) }
            }
        }
    }
}
