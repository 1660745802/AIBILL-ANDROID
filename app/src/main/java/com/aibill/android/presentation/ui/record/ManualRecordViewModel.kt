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
        /**
         * 保存成功。自带展示文案，让 UI 层不必再靠关键字匹配去分辨成功/失败
         * （原先 UI 还要额外弹一个 Toast，形成双重反馈）。
         */
        data class SaveSuccess(val message: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    /**
     * 当前 type 对应的分类订阅 Job，PR #36：切类型时取消旧 Job，
     * 避免 collect 协程叠加浪费 Room 订阅。
     */
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
                is Result.Success -> {
                    _uiState.update { it.copy(availableTags = result.data) }
                }
                else -> Unit // 静默失败，标签建议非核心功能
            }
        }
    }

    fun onTagAdded(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return
        val current = _uiState.value.tags
        if (trimmed in current) return
        _uiState.update { it.copy(tags = current + trimmed) }
    }

    fun onTagRemoved(tag: String) {
        _uiState.update { it.copy(tags = it.tags - tag) }
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

    /**
     * 直接设置金额文本（用于系统键盘输入场景）。
     *
     * 历史 bug：直接 `Math.round(text.toDouble() * 100).toInt()` 有两个问题——
     * 1. `.toInt()` 对超范围值**静默截断**（Long.toInt 不抛异常），
     *    用户输入一串长数字会得到一个完全错误的金额并写进账单；
     * 2. 本路径**没有**长度限制（onAmountInput 有限制，两条路径不一致）。
     * 现在统一走 [AmountUtils.parseExpression]（内部 try/catch + 上限拦截，返回 null），
     * 并复用 [MAX_AMOUNT_TEXT_LENGTH] 与 [AmountUtils.MAX_AMOUNT_FEN] 两个上限。
     *
     * @param text 已经过格式校验的纯数字+小数点字符串
     */
    fun onAmountTextChanged(text: String) {
        val truncated = text.take(MAX_AMOUNT_TEXT_LENGTH)
        val fen = AmountUtils.parseExpression(truncated)
        _uiState.update {
            if (fen == null) {
                // 非法 / 溢出：保留用户输入的文本（方便看到并手动修），金额保持上一次有效值
                it.copy(amountText = truncated)
            } else {
                it.copy(amountText = truncated, amountFen = fen)
            }
        }
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

    fun onCategorySelected(id: Int) {
        _uiState.update { it.copy(selectedCategoryId = id) }
    }

    fun onAiInputChanged(text: String) {
        _uiState.update { it.copy(aiInputText = text) }
    }

    /**
     * AI 快捷填充：输入自然语言后调用 AI 解析，自动填充金额/分类/备注。
     */
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
                        // 填充金额（显示走 AmountUtils，不在 UI 层做 Double 换算）
                        if (first.amount > 0) {
                            val yuanText = AmountUtils.fenToYuan(first.amount)
                            _uiState.update { it.copy(amountText = yuanText, amountFen = first.amount) }
                        }
                        // 填充类型
                        first.type?.let { type ->
                            val typeStr = type.value // TransactionType enum → string
                            onTypeChanged(typeStr)
                        }
                        // 填充分类
                        first.categoryId?.let { catId ->
                            _uiState.update { it.copy(selectedCategoryId = catId) }
                        }
                        // 填充备注
                        first.description?.let { desc ->
                            _uiState.update { it.copy(description = desc) }
                        }
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

    fun onDescriptionChanged(text: String) {
        _uiState.update { it.copy(description = text) }
    }

    fun onDateChanged(date: String) {
        _uiState.update { it.copy(date = date) }
    }

    /**
     * 保存。
     *
     * ## 成功判据在服务端手里
     *
     * `createTransactions` 只有在服务端回执（`created` / `duplicates`）+ `client_id` 匹配时才返回
     * [Result.Success]，所以走到这里就意味着「真的记上了」。
     *
     * ## 失败当场告知，且**保留表单输入**
     *
     * 任何失败都只报错、不落库、不清表单：用户原地改一改就能重试。
     * 无网时给的是「没网，记不上」而不是兜底成一条待补传的本地记录——
     * 本工程没有离线队列，写了就是谎报。
     */
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
                time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                source = TransactionSource.MANUAL,
                tags = state.tags.ifEmpty { null },
            )

            val result = transactionRepository.createTransactions(listOf(transaction))
            when (result) {
                is Result.Success -> {
                    _uiEvent.send(UiEvent.SaveSuccess("已记录，继续记下一笔"))
                    streakTracker.onTransactionRecorded()
                    resetForm()
                }
                is Result.Error -> {
                    // 没记上就是没记上：不落库、不清表单，用户当场重试。
                    // 无网与业务失败分开措辞，避免把「422 让你改字段」说成「没网」。
                    _uiEvent.send(
                        UiEvent.ShowToast(
                            if (result.code == Result.ERROR_NETWORK) {
                                "没网，记不上"
                            } else {
                                "保存失败: ${result.message}"
                            },
                        ),
                    )
                }
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private fun resetForm() {
        val type = _uiState.value.type
        val categories = _uiState.value.categories
        val availableTags = _uiState.value.availableTags
        _uiState.update {
            RecordUiState(
                type = type,
                categories = categories,
                date = LocalDate.now().toString(),
                availableTags = availableTags,
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
