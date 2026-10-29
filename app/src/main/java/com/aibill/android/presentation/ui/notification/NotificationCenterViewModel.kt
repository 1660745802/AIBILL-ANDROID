package com.aibill.android.presentation.ui.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.model.NotificationRecord
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.notification.ReviewQueue
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.RecordOutcome
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知中心 ViewModel。**已重构**：移除所有 DAO/AppLogger/Context 直接依赖，
 * 通知记录读取全部走 `ReviewQueue` module，不直接碰 DAO。
 */
@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val reviewQueue: ReviewQueue,
    private val recordIntake: RecordIntake,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val categoryLearningEngine: CategoryLearningEngine,
    private val accountRepository: AccountRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()

        /**
         * 忽略后给一次「撤销」出口（`ignored → parsed` 纯状态回滚，无入账副作用）。
         * 界面用带 action 的 Snackbar 渲染；用户点「撤销」再调 [restoreItem]。
         */
        data class ShowUndoIgnored(val message: String, val recordId: Long) : UiEvent()
    }

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _categoriesByType = MutableStateFlow<Map<String, List<Category>>>(emptyMap())
    val categoriesByType: StateFlow<Map<String, List<Category>>> = _categoriesByType.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    val accounts: StateFlow<List<Account>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nlsConnected = MutableStateFlow(false)
    val nlsConnected: StateFlow<Boolean> = _nlsConnected.asStateFlow()

    init {
        observeCategories()
        checkNlsStatus()
        loadAvailableTags()
        viewModelScope.launch { accountRepository.syncAccounts() }
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            when (val result = transactionRepository.getTags()) {
                is Result.Success -> _availableTags.value = result.data
                else -> Unit
            }
        }
    }

    fun checkNlsStatus() {
        val enabledListeners = android.provider.Settings.Secure.getString(
            appContext.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        _nlsConnected.value = enabledListeners.contains(appContext.packageName)
    }

    private fun observeCategories() {
        viewModelScope.launch {
            categoryRepository.observeCategories("expense").collect { list ->
                _categoriesByType.update { it + ("expense" to list) }
            }
        }
        viewModelScope.launch {
            categoryRepository.observeCategories("income").collect { list ->
                _categoriesByType.update { it + ("income" to list) }
            }
        }
    }

    /**
     * 通知中心展示：待确认全部 + 已确认最近 24h。
     */
    val allNotifications: StateFlow<List<NotificationRecord>> =
        reviewQueue.observe(
            System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = reviewQueue.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun confirmItem(id: Long) {
        viewModelScope.launch {
            val record = reviewQueue.findById(id) ?: return@launch
            if (record.status == NotificationRecord.STATUS_CONFIRMED) {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
                return@launch
            }
            if (record.parsedType == "transfer") {
                _uiEvent.send(UiEvent.ShowToast("转账请先编辑，选择不同的有效转出和转入账户"))
                return@launch
            }
            val outcome = insertTransaction(
                recordId = id,
                type = record.parsedType ?: "expense",
                amountCents = record.parsedAmount ?: 0,
                description = record.parsedDescription,
                packageName = record.packageName,
            )
            // 没记上就不许说「已确认」：记录原样留在待审池，用户可以再试一次
            _uiEvent.send(
                UiEvent.ShowToast(
                    outcome.failure?.userMessage ?: "已确认",
                ),
            )
        }
    }

    fun confirmWithEdit(
        id: Long,
        type: String,
        amountCents: Int,
        description: String,
        categoryId: Int?,
        tags: List<String> = emptyList(),
        accountId: Int? = null,
        targetAccountId: Int? = null,
    ) {
        viewModelScope.launch {
            if (amountCents <= 0) {
                _uiEvent.send(UiEvent.ShowToast("请输入有效金额"))
                return@launch
            }
            val record = reviewQueue.findById(id) ?: return@launch
            if (record.status == NotificationRecord.STATUS_CONFIRMED) {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
                return@launch
            }
            if (type == "transfer" && !validTransferAccounts(accountId, targetAccountId)) {
                _uiEvent.send(UiEvent.ShowToast("请编辑转账，选择不同的有效转出和转入账户"))
                return@launch
            }
            val outcome = insertTransaction(
                recordId = id,
                type = type,
                amountCents = amountCents,
                description = description.ifBlank { record.parsedDescription },
                packageName = record.packageName,
                categoryId = categoryId,
                tags = tags,
                accountId = accountId,
                targetAccountId = targetAccountId,
            )
            val failure = outcome.failure
            if (failure != null) {
                // 服务端没确认：不学习（学的是「用户改对了分类」，账都没记上谈不上改对），
                // 记录仍停在待审池，Toast 如实报错。
                _uiEvent.send(UiEvent.ShowToast(failure.userMessage))
                return@launch
            }
            val learnDesc = description.ifBlank { record.parsedDescription }
            if (!outcome.alreadyRecorded && categoryId != null && !learnDesc.isNullOrBlank()) {
                categoryLearningEngine.learnFromCorrection(learnDesc.trim(), categoryId)
            }
            _uiEvent.send(UiEvent.ShowToast(if (outcome.alreadyRecorded) "该笔已确认，本次未重复记账" else "已记账 ✓"))
        }
    }

    private fun validTransferAccounts(accountId: Int?, targetAccountId: Int?): Boolean =
        accountId != null && targetAccountId != null && accountId != targetAccountId &&
            accounts.value.any { it.id == accountId } && accounts.value.any { it.id == targetAccountId }

    /**
     * 把一条通知翻译成入账意图。送服务端确认、落缓存、刷 widget 都在入账 module 里。
     *
     * @return 入账结果；调用方据此决定是报成功还是如实报错
     */
    private suspend fun insertTransaction(
        recordId: Long,
        type: String,
        amountCents: Int,
        description: String?,
        packageName: String,
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
        accountId: Int? = null,
        targetAccountId: Int? = null,
    ): RecordOutcome {
        val typeKey = if (type == "income") "income" else "expense"
        val category = categoryId?.let { id ->
            _categoriesByType.value[typeKey]?.firstOrNull { it.id == id }
        }

        return recordIntake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = recordId,
                packageName = packageName,
                amount = amountCents,
                type = type,
                description = description,
                categoryId = categoryId,
                categoryName = category?.name,
                categoryIcon = category?.icon,
                tags = tags,
                accountId = accountId,
                targetAccountId = targetAccountId,
            )
        )
    }

    fun ignoreItem(id: Long) {
        viewModelScope.launch {
            reviewQueue.ignore(id)
            // 带撤销出口：点错一次不应导致静默漏账（P0-1）
            _uiEvent.send(UiEvent.ShowUndoIgnored("已忽略", id))
        }
    }

    /** 恢复一条被忽略的记录（通知中心「已忽略」区的「恢复」按钮 / Snackbar 撤销）。 */
    fun restoreItem(id: Long) {
        viewModelScope.launch {
            reviewQueue.restore(id)
            _uiEvent.send(UiEvent.ShowToast("已恢复"))
        }
    }
}
