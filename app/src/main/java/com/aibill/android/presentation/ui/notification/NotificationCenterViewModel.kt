package com.aibill.android.presentation.ui.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.model.Category
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
    }

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _categoriesByType = MutableStateFlow<Map<String, List<Category>>>(emptyMap())
    val categoriesByType: StateFlow<Map<String, List<Category>>> = _categoriesByType.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    private val _nlsConnected = MutableStateFlow(false)
    val nlsConnected: StateFlow<Boolean> = _nlsConnected.asStateFlow()

    init {
        observeCategories()
        checkNlsStatus()
        loadAvailableTags()
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
    val allNotifications: StateFlow<List<NotificationRecordEntity>> =
        reviewQueue.observe(
            System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = reviewQueue.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun confirmItem(id: Long) {
        viewModelScope.launch {
            val record = reviewQueue.findById(id) ?: return@launch
            if (record.status == "confirmed") {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
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
    ) {
        viewModelScope.launch {
            if (amountCents <= 0) {
                _uiEvent.send(UiEvent.ShowToast("请输入有效金额"))
                return@launch
            }
            val record = reviewQueue.findById(id) ?: return@launch
            if (record.status == "confirmed") {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
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
            )
            val failure = outcome.failure
            if (failure != null) {
                // 服务端没确认：不学习（学的是「用户改对了分类」，账都没记上谈不上改对），
                // 记录仍停在待审池，Toast 如实报错。
                _uiEvent.send(UiEvent.ShowToast(failure.userMessage))
                return@launch
            }
            val learnDesc = description.ifBlank { record.parsedDescription }
            if (categoryId != null && !learnDesc.isNullOrBlank()) {
                categoryLearningEngine.learnFromCorrection(learnDesc.trim(), categoryId)
            }
            _uiEvent.send(UiEvent.ShowToast("已记账 ✓"))
        }
    }

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
            )
        )
    }

    fun ignoreItem(id: Long) {
        viewModelScope.launch {
            reviewQueue.ignore(id)
            _uiEvent.send(UiEvent.ShowToast("已忽略"))
        }
    }
}
