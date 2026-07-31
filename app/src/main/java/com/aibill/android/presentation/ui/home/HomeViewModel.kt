package com.aibill.android.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.repository.BudgetRepository
import com.aibill.android.domain.repository.StatsRepository
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
import com.aibill.android.domain.usecase.StreakInfo
import com.aibill.android.domain.usecase.StreakTracker
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
    // PR M8：statsApi → StatsRepository
    private val statsRepository: StatsRepository,
    private val budgetRepository: BudgetRepository,
    private val notificationRecordDao: com.aibill.android.data.local.dao.NotificationRecordDao,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val streakTracker: StreakTracker,
    private val appLogger: com.aibill.android.util.AppLogger,
) : ViewModel() {

    companion object {
        val DEFAULT_QUICK_PHRASES: List<Pair<String, String>> = listOf(
            "咖啡 30" to "☕",
            "午餐 25" to "🍜",
            "地铁 3" to "🚇",
            "早餐 10" to "🍳",
            "晚餐 35" to "🍽️",
            "超市 80" to "🛒",
        )
    }

    data class HomeUiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val monthlyExpense: Int = 0,
        val monthlyBudget: Int? = null,
        val inputText: String = "",
        val isParsing: Boolean = false,
        val aiParseResults: List<AiParseResult>? = null,
        val todayTransactions: List<Transaction> = emptyList(),
        val pendingNotificationCount: Int = 0,
        val pendingSyncCount: Int = 0,
        val isSyncing: Boolean = false,
        /** AI 编辑弹窗/手动记账等需要的可选分类列表（按 type 过滤） */
        val categoriesByType: Map<String, List<Category>> = emptyMap(),
        val availableTags: List<String> = emptyList(),
        val quickPhrases: List<Pair<String, String>> = DEFAULT_QUICK_PHRASES,
        /** 最近7天日支出趋势 (dayLabel, amountCents) */
        val weeklyTrend: List<Pair<String, Int>> = emptyList(),
        /** 连续记账天数 */
        val streakInfo: StreakInfo = StreakInfo(),
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
        observePendingSyncCount()
        observeCategories()
        observeStreak()
        loadAvailableTags()
        loadQuickPhrases()
    }

    private fun observeStreak() {
        viewModelScope.launch {
            streakTracker.checkAndResetIfNeeded()
            streakTracker.streakInfo.collect { info ->
                _uiState.update { it.copy(streakInfo = info) }
            }
        }
    }

    private fun loadWeeklyTrend() {
        viewModelScope.launch {
            val now = LocalDate.now()
            when (val result = statsRepository.getTrend(now.year, now.monthValue, "day", "expense")) {
                is Result.Success -> {
                    // 取最近7天
                    val last7 = result.data.takeLast(7).map { point ->
                        val dayLabel = point.date.takeLast(2) // "2026-07-28" -> "28"
                        dayLabel to point.amount
                    }
                    _uiState.update { it.copy(weeklyTrend = last7) }
                }
                else -> Unit
            }
        }
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

    private fun observePendingSyncCount() {
        viewModelScope.launch {
            transactionRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingSyncCount = count) }
            }
        }
    }

    fun triggerSync() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            when (val result = transactionRepository.syncPending()) {
                is Result.Success -> {
                    _uiEvent.emit(UiEvent.ShowToast("同步完成"))
                    refreshData()
                }
                is Result.Error -> {
                    _uiEvent.emit(UiEvent.ShowError("同步失败: ${result.message}"))
                }
                is Result.Loading -> Unit
            }
            _uiState.update { it.copy(isSyncing = false) }
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
                val deferred5 = async { loadMonthlyBudget() }
                awaitAll(deferred1, deferred2, deferred3, deferred4, deferred5)
                loadAvailableTags()
                loadQuickPhrases()
                loadWeeklyTrend()
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
            appLogger.info("HOME", "AI解析请求: input=${input.take(50)}")
            when (val result = aiRepository.parseInput(input)) {
                is Result.Success -> {
                    val data = result.data
                    if (data.isEmpty()) {
                        appLogger.info("HOME", "AI解析成功但结果为空")
                        _uiState.update { it.copy(isParsing = false) }
                        _uiEvent.emit(UiEvent.ShowToast("未能识别有效的记账信息"))
                    } else {
                        appLogger.info("HOME", "AI解析成功: ${data.size}条结果")
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
                    appLogger.error("HOME", "AI解析失败: code=${result.code} msg=${result.message}")
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
            appLogger.info("HOME", "onConfirmItem: amount=${item.amount} type=${item.type}")
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
                    streakTracker.onTransactionRecorded()
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
        accountId: Int? = null,
        targetAccountId: Int? = null,
        tags: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            appLogger.info("HOME", "onConfirmEditedItem: amount=$amount type=$type catId=$categoryId")
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
                categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                categoryName = newCategory?.name ?: original.categoryName,
                categoryIcon = newCategory?.icon ?: original.categoryIcon,
                description = description.ifBlank { null },
                accountId = accountId,
                targetAccountId = targetAccountId,
            )
            // 若用户修改了分类，学习新规则
            if (newCategory != null && categoryId != original.categoryId) {
                val desc = edited.description
                if (!desc.isNullOrBlank()) {
                    categoryLearningEngine.learnFromCorrection(desc, categoryId)
                }
            }
            when (val result =
                transactionRepository.createTransactions(listOf(edited.toTransaction(tags)))) {
                is Result.Success -> {
                    val remaining =
                        _uiState.value.aiParseResults?.filter { it !== original }
                    _uiState.update {
                        it.copy(aiParseResults = remaining?.ifEmpty { null })
                    }
                    _uiEvent.emit(UiEvent.ShowToast("已记录"))
                    streakTracker.onTransactionRecorded()
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
                    streakTracker.onTransactionRecorded()
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
                    it.copy(isLoading = false, todayTransactions = result.data.items.distinctBy { t -> t.clientId })
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

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is Result.Success -> _uiState.update { it.copy(availableTags = result.data) }
                else -> Unit
            }
        }
    }

    private fun loadQuickPhrases() {
        viewModelScope.launch {
            val thirtyDaysAgo = LocalDate.now().minusDays(30)
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            val todayStr = today
            when (val result = transactionRepository.getTransactions(
                page = 1,
                pageSize = 200,
                startDate = thirtyDaysAgo,
                endDate = todayStr,
            )) {
                is Result.Success -> {
                    val phrases = result.data.items
                        .filter { !it.description.isNullOrBlank() }
                        .groupBy { Triple(it.description, it.amount, it.categoryIcon) }
                        .entries
                        .sortedByDescending { it.value.size }
                        .take(6)
                        .map { (key, _) ->
                            val (description, amount, icon) = key
                            val yuanAmount = amount / 100
                            val remainder = amount % 100
                            val amountStr = if (remainder == 0) {
                                "$yuanAmount"
                            } else {
                                "%.2f".format(amount / 100.0)
                            }
                            val text = "$description $amountStr"
                            val emoji = icon ?: "📝"
                            text to emoji
                        }
                    if (phrases.isNotEmpty()) {
                        _uiState.update { it.copy(quickPhrases = phrases) }
                    }
                    // If no valid phrases found, keep the default fallback
                }
                is Result.Error -> {
                    Timber.e("加载快捷短语失败: ${result.message}")
                    // Keep fallback phrases
                }
                is Result.Loading -> Unit
            }
        }
    }

    /**
     * 直接调用 StatsApi.getSummary 获取月度支出，避免拉取全部流水
     * 同时更新 Widget 数据
     * PR #64：失败时 emit UiEvent.ShowError 让 UI 显示 Snackbar+重试，
     * 之前只 Timber.e 日志用户完全感知不到
     */
    private suspend fun loadMonthlyExpense() {
        val now = LocalDate.now()
        when (val result = statsRepository.getSummary(now.year, now.monthValue)) {
            is Result.Success -> {
                _uiState.update { it.copy(monthlyExpense = result.data.expense) }
                WidgetDataUpdater.updateMonthlySummary(
                    context = application,
                    expenseCents = result.data.expense,
                    incomeCents = result.data.income,
                )
            }
            is Result.Error -> {
                Timber.e("加载月度支出失败: ${result.message}")
                _uiEvent.emit(UiEvent.ShowError("加载月度数据失败: ${result.message}"))
            }
            is Result.Loading -> Unit
        }
    }

    /**
     * 加载月度总预算（categoryId == 0 表示总预算）
     * 用于首页展示"支出 / 预算 xxx 元"进度
     */
    private suspend fun loadMonthlyBudget() {
        val now = LocalDate.now()
        when (val result = budgetRepository.getBudgets(now.year, now.monthValue)) {
            is Result.Success -> {
                val totalBudget = result.data.firstOrNull { it.categoryId == 0 }
                _uiState.update { it.copy(monthlyBudget = totalBudget?.amount) }
            }
            is Result.Error -> {
                Timber.e("加载月度预算失败: ${result.message}")
                // 预算加载失败不影响主流程，静默处理
            }
            is Result.Loading -> Unit
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            launch { loadTodayTransactions() }
            launch { loadMonthlyExpense() }
            launch { loadMonthlyBudget() }
        }
    }

    private fun AiParseResult.toTransaction(extraTags: List<String> = emptyList()): Transaction = Transaction(
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
        time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
        source = TransactionSource.AI,
        tags = extraTags.ifEmpty { null },
    )
}
