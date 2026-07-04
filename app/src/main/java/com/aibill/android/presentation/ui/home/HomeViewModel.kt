package com.aibill.android.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.StatsApi
import com.aibill.android.domain.model.AiParseResult
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.AiRepository
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import android.app.Application
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import com.aibill.android.service.WidgetDataUpdater

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val aiRepository: AiRepository,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val statsApi: StatsApi,
    private val notificationRecordDao: com.aibill.android.data.local.dao.NotificationRecordDao,
    private val categoryLearningEngine: CategoryLearningEngine,
) : ViewModel() {

    data class HomeUiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val monthlyExpense: Int = 0,
        val inputText: String = "",
        val isParsing: Boolean = false,
        val aiParseResults: List<AiParseResult>? = null,
        val todayTransactions: List<Transaction> = emptyList(),
        val pendingNotificationCount: Int = 0,
        /** AI 编辑弹窗/手动记账等需要的可选分类列表（按 type 过滤） */
        val categoriesByType: Map<String, List<Category>> = emptyMap(),
        val error: String? = null,
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class ShowError(val message: String) : UiEvent()
        /**
         * AI 解析失败（PRD §4.1 5001）时提示用户切换手动记账。
         * prefillInput 是用户原本输入的文本，手动记账页可预填。
         */
        data class AiFallbackToManual(val prefillInput: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val today: String
        get() = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    init {
        refresh()
        observePendingNotifications()
        observeCategories()
    }

    private fun observeCategories() {
        // 支出分类
        viewModelScope.launch {
            categoryRepository.observeCategories("expense").collect { list ->
                _uiState.update {
                    it.copy(categoriesByType = it.categoriesByType + ("expense" to list))
                }
            }
        }
        // 收入分类
        viewModelScope.launch {
            categoryRepository.observeCategories("income").collect { list ->
                _uiState.update {
                    it.copy(categoriesByType = it.categoriesByType + ("income" to list))
                }
            }
        }
    }

    private fun observePendingNotifications() {
        viewModelScope.launch {
            notificationRecordDao.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingNotificationCount = count) }
            }
        }
    }

    /**
     * 下拉刷新：重新加载月度支出 + 今日流水 + 同步分类账户
     * 月度支出和今日流水分开处理，互不影响
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            // PR #35：awaitAll 等所有子协程完成后再置 isRefreshing=false，
            // 避免下拉指示器瞬间消失（之前外层 launch{} 后续语句不被 await 子 launch）
            try {
                val deferred1 = async { categoryRepository.syncCategories() }
                val deferred2 = async { accountRepository.syncAccounts() }
                val deferred3 = async { loadMonthlyExpense() }
                val deferred4 = async {
                    val success = loadTodayTransactions()
                    if (!success) {
                        _uiEvent.emit(UiEvent.ShowError("加载今日流水失败，请检查网络"))
                    }
                }
                awaitAll(deferred1, deferred2, deferred3, deferred4)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onParseInput() {
        val input = _uiState.value.inputText.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isParsing = true, error = null) }
            when (val result = aiRepository.parseInput(input)) {
                is Result.Success -> {
                    val data = result.data
                    if (data.isEmpty()) {
                        _uiState.update { it.copy(isParsing = false) }
                        _uiEvent.emit(UiEvent.ShowToast("未能识别有效的记账信息"))
                    } else {
                        _uiState.update {
                            it.copy(
                                isParsing = false,
                                aiParseResults = data,
                                inputText = "",
                            )
                        }
                    }
                }
                is Result.Error -> {
                    Timber.e("AI 解析失败: ${result.message}")
                    _uiState.update {
                        it.copy(isParsing = false, error = result.message)
                    }
                    // PRD §4.1：AI 解析失败（5001）应提示用户切换手动记账
                    // PRD 错误码表：5001 = AI 解析失败，5002 = AI 服务不可用
                    if (result.code == 5001 || result.code == 5002) {
                        _uiEvent.emit(UiEvent.AiFallbackToManual(input))
                    } else {
                        _uiEvent.emit(UiEvent.ShowError(result.message))
                    }
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun onConfirmItem(item: AiParseResult) {
        viewModelScope.launch {
            val transaction = item.toTransaction()
            when (val result =
                transactionRepository.createTransactions(listOf(transaction))) {
                is Result.Success -> {
                    val remaining =
                        _uiState.value.aiParseResults?.filter { it !== item }
                    _uiState.update {
                        it.copy(aiParseResults = remaining?.ifEmpty { null })
                    }
                    _uiEvent.emit(UiEvent.ShowToast("已记录"))
                    refreshData()
                }
                is Result.Error -> {
                    _uiEvent.emit(
                        UiEvent.ShowError("保存失败: ${result.message}")
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }

    /**
     * 用户在确认前编辑了金额/类型/分类/备注后再保存
     */
    fun onConfirmEditedItem(
        original: AiParseResult,
        amount: Int,
        type: TransactionType,
        categoryId: Int,
        description: String,
    ) {
        viewModelScope.launch {
            if (amount <= 0) {
                _uiEvent.emit(UiEvent.ShowError("金额必须大于0"))
                return@launch
            }
            // 根据新分类 id 找到对应的 name/icon（保持列表展示一致）
            val categoryList = _uiState.value.categoriesByType[
                if (type == TransactionType.EXPENSE) "expense" else "income"
            ].orEmpty()
            val newCategory = categoryList.firstOrNull { it.id == categoryId }
            val edited = original.copy(
                amount = amount,
                type = type,
                categoryId = categoryId,
                categoryName = newCategory?.name ?: original.categoryName,
                categoryIcon = newCategory?.icon ?: original.categoryIcon,
                description = description.ifBlank { null },
            )
            // 若用户修改了分类，学习新规则
            if (newCategory != null && categoryId != original.categoryId) {
                val desc = edited.description
                if (!desc.isNullOrBlank()) {
                    categoryLearningEngine.learnFromCorrection(desc, categoryId)
                }
            }
            when (val result =
                transactionRepository.createTransactions(listOf(edited.toTransaction()))) {
                is Result.Success -> {
                    val remaining =
                        _uiState.value.aiParseResults?.filter { it !== original }
                    _uiState.update {
                        it.copy(aiParseResults = remaining?.ifEmpty { null })
                    }
                    _uiEvent.emit(UiEvent.ShowToast("已记录"))
                    refreshData()
                }
                is Result.Error -> {
                    _uiEvent.emit(UiEvent.ShowError("保存失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun onConfirmAll() {
        val items = _uiState.value.aiParseResults ?: return
        viewModelScope.launch {
            val transactions = items.map { it.toTransaction() }
            when (val result =
                transactionRepository.createTransactions(transactions)) {
                is Result.Success -> {
                    _uiState.update { it.copy(aiParseResults = null) }
                    _uiEvent.emit(UiEvent.ShowToast("已记录 ${items.size} 笔"))
                    refreshData()
                }
                is Result.Error -> {
                    _uiEvent.emit(
                        UiEvent.ShowError("保存失败: ${result.message}")
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun onDismissResults() {
        _uiState.update { it.copy(aiParseResults = null) }
    }

    private suspend fun loadTodayTransactions(): Boolean {
        _uiState.update { it.copy(isLoading = true) }
        return when (val result = transactionRepository.getTransactions(
            page = 1,
            pageSize = 50,
            startDate = today,
            endDate = today,
        )) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(isLoading = false, todayTransactions = result.data.items)
                }
                true
            }
            is Result.Error -> {
                _uiState.update { it.copy(isLoading = false) }
                Timber.e("加载今日流水失败: ${result.message}")
                false
            }
            is Result.Loading -> true
        }
    }

    /**
     * 直接调用 StatsApi.getSummary 获取月度支出，避免拉取全部流水
     * 同时更新 Widget 数据
     */
    private suspend fun loadMonthlyExpense() {
        val now = LocalDate.now()
        try {
            val response = statsApi.getSummary(now.year, now.monthValue)
            if (response.code == 0 && response.data != null) {
                _uiState.update { it.copy(monthlyExpense = response.data.expense) }
                // 更新 Widget 缓存数据
                WidgetDataUpdater.updateMonthlySummary(
                    context = application,
                    expenseCents = response.data.expense,
                    incomeCents = response.data.income
                )
            } else {
                Timber.e("加载月度支出失败: ${response.message}")
            }
        } catch (e: Exception) {
            Timber.e(e, "加载月度支出异常")
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            launch { loadTodayTransactions() }
            launch { loadMonthlyExpense() }
        }
    }

    private fun AiParseResult.toTransaction(): Transaction = Transaction(
        clientId = UUID.randomUUID().toString(),
        type = type,
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        accountId = accountId,
        accountName = accountName,
        targetAccountId = targetAccountId,
        targetAccountName = targetAccountName,
        description = description,
        date = date,
        source = TransactionSource.AI,
    )
}
