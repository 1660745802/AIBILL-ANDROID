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
    private val accountRepository: com.aibill.android.domain.repository.AccountRepository,
    private val aiRepository: com.aibill.android.domain.repository.AiRepository,
    private val streakTracker: com.aibill.android.domain.usecase.StreakTracker,
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
        val tags: List<String> = emptyList(),
        val availableTags: List<String> = emptyList(),
        /** AI 快捷输入 */
        val aiInputText: String = "",
        val isAiParsing: Boolean = false,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        /** 成功事件与错误文案分离，UI 不靠关键词判定结果。 */
        data class SaveSuccess(val message: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    // 未确认草稿在此 ViewModel 生命周期内复用整份请求（含 ID/时间）。
    // 任何实际表单编辑都废弃旧 ID，即使随后改回原值；相同值回调、元数据刷新不换 ID。
    private var pendingTransaction: Transaction? = null

    private fun editForm(transform: (RecordUiState) -> RecordUiState) {
        val current = _uiState.value
        val edited = transform(current)
        if (edited != current) pendingTransaction = null
        _uiState.value = edited
    }

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    /** 切类型时取消旧分类订阅，避免 collect 协程叠加。 */
    private var categoriesJob: kotlinx.coroutines.Job? = null

    init {
        loadCategories(_uiState.value.type)
        loadAccounts()
        loadAvailableTags()
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is Result.Success -> _uiState.update { it.copy(availableTags = result.data) }
                else -> Unit // 静默失败，标签建议非核心功能
            }
        }
    }

    fun onTagAdded(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return
        val current = _uiState.value.tags
        if (trimmed in current) return
        editForm { it.copy(tags = current + trimmed) }
    }

    fun onTagRemoved(tag: String) = editForm { it.copy(tags = it.tags - tag) }

    fun onAccountSelected(id: Int) = editForm { it.copy(accountId = id) }

    fun onTargetAccountSelected(id: Int) = editForm { it.copy(targetAccountId = id) }

    fun onTypeChanged(type: String) {
        if (type == _uiState.value.type) return
        editForm { it.copy(type = type, selectedCategoryId = null) }
        loadCategories(type)
    }

    /** 系统键盘与计算器共用长度/金额上限；编辑中保留不完整表达式，旧金额仅供预览。 */
    fun onAmountTextChanged(text: String) {
        val truncated = text.take(MAX_AMOUNT_TEXT_LENGTH)
        val fen = AmountUtils.parseExpression(truncated)
        editForm { it.copy(amountText = truncated, amountFen = fen ?: it.amountFen) }
    }

    fun onAmountInput(char: String) {
        val current = _uiState.value.amountText
        // 限制长度防止溢出
        if (current.length >= MAX_AMOUNT_TEXT_LENGTH) return
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

        onAmountTextChanged(current + char)
    }

    fun onAmountDelete() {
        val current = _uiState.value.amountText
        if (current.isEmpty()) return
        val newText = current.dropLast(1)
        val parsed = if (newText.isEmpty()) 0 else AmountUtils.parseExpression(newText) ?: _uiState.value.amountFen
        editForm { it.copy(amountText = newText, amountFen = parsed) }
    }

    fun onCategorySelected(id: Int) {
        editForm { it.copy(selectedCategoryId = id) }
    }

    fun onAiInputChanged(text: String) {
        _uiState.update { it.copy(aiInputText = text) }
    }

    /** AI 快捷填充金额/分类/备注。 */
    fun onAiParse() {
        val input = _uiState.value.aiInputText.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isAiParsing = true) }
            when (val result = aiRepository.parseInput(input)) {
                is Result.Success -> {
                    val items = result.data
                    if (items.isNotEmpty()) {
                        val first = items.first()
                        if (first.amount > 0) onAmountTextChanged(AmountUtils.fenToYuan(first.amount))
                        first.type?.let { onTypeChanged(it.value) }
                        first.categoryId?.let(::onCategorySelected)
                        first.description?.let(::onDescriptionChanged)
                        _uiState.update { it.copy(isAiParsing = false, aiInputText = "") }
                        _uiEvent.send(UiEvent.ShowToast("✨ AI 已填充"))
                    } else {
                        _uiState.update { it.copy(isAiParsing = false) }
                        _uiEvent.send(UiEvent.ShowToast("未能识别，请手动输入"))
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isAiParsing = false) }
                    _uiEvent.send(UiEvent.ShowToast("AI 解析失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun onDescriptionChanged(text: String) = editForm { it.copy(description = text) }

    fun onDateChanged(date: String) = editForm { it.copy(date = date) }

    /**
     * 仓库核验服务端 created/duplicates + client_id 后才返回成功。
     * 失败保留表单和未确认请求；不落本地行，也不承诺离线补传。
     */
    @Synchronized
    fun onSave() {
        val state = _uiState.value
        if (state.isSaving) return
        val amount = AmountUtils.parseExpression(state.amountText)
        val error = when {
            state.amountText.isBlank() -> "请输入金额"
            amount == null || amount > AmountUtils.MAX_AMOUNT_FEN -> "金额表达式无效或超出上限，请检查"
            amount <= 0 -> "金额必须大于 0"
            state.selectedCategoryId == null && state.type != "transfer" -> "请选择分类"
            else -> null
        }
        if (error != null) {
            viewModelScope.launch { _uiEvent.send(UiEvent.ShowToast(error)) }
            return
        }
        val transaction = pendingTransaction ?: buildTransaction(state, requireNotNull(amount)).also {
            pendingTransaction = it
        }
        // 必须在 launch 之前占位：同一调度轮次的双击也只能发出一个请求。
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                when (val result = transactionRepository.createTransactions(listOf(transaction))) {
                    is Result.Success -> {
                        // 保存途中用户已编辑的是下一份草稿，不能被旧回执清掉。
                        if (pendingTransaction === transaction) {
                            pendingTransaction = null
                            resetForm()
                        }
                        _uiEvent.send(UiEvent.SaveSuccess("已记录，继续记下一笔"))
                        streakTracker.onTransactionRecorded()
                    }
                    is Result.Error -> _uiEvent.send(
                        UiEvent.ShowToast(
                            if (result.code == Result.ERROR_NETWORK) "没网，记不上"
                            else "保存失败: ${result.message}",
                        ),
                    )
                    is Result.Loading -> Unit
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun buildTransaction(state: RecordUiState, amount: Int): Transaction {
        val category = state.categories.find { it.id == state.selectedCategoryId }
        return Transaction(
            clientId = UUID.randomUUID().toString(),
            type = TransactionType.fromValue(state.type) ?: TransactionType.EXPENSE,
            amount = amount,
            categoryId = state.selectedCategoryId,
            categoryName = category?.name,
            categoryIcon = category?.icon,
            accountId = state.accountId,
            targetAccountId = state.targetAccountId,
            description = state.description.ifBlank { null },
            date = state.date,
            time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            source = TransactionSource.MANUAL,
            tags = state.tags.ifEmpty { null },
        )
    }

    private fun resetForm() {
        _uiState.update {
            RecordUiState(
                type = it.type,
                categories = it.categories,
                isSaving = it.isSaving,
                availableTags = it.availableTags,
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

    private companion object {
        /** 金额输入文本长度上限（两条输入路径统一，避免系统键盘路径无限制） */
        const val MAX_AMOUNT_TEXT_LENGTH = 20
    }
}
