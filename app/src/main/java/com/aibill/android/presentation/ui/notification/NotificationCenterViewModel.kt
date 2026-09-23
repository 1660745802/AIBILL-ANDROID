package com.aibill.android.presentation.ui.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.NotificationRecordRepository
import com.aibill.android.domain.repository.PendingTransactionRepository
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.service.SyncScheduler
import com.aibill.android.service.WidgetDataUpdater
import com.aibill.android.util.NotificationSourceMapping
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * 通知中心 ViewModel。**已重构**：移除所有 DAO/AppLogger/Context 直接依赖，
 * 全部走 Repository 接口。
 */
@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val notificationRepository: NotificationRecordRepository,
    private val pendingTransactionRepository: PendingTransactionRepository,
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

    val pendingNotifications: StateFlow<List<NotificationRecordEntity>> =
        notificationRepository.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val confirmedNotifications: StateFlow<List<NotificationRecordEntity>> =
        notificationRepository.observeConfirmed()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 通知中心展示：待确认全部 + 已确认最近 24h。
     */
    val allNotifications: StateFlow<List<NotificationRecordEntity>> =
        notificationRepository.observeAllWithConfirmedSince(
            System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = notificationRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _isConfirming = MutableStateFlow(false)
    val isConfirming: StateFlow<Boolean> = _isConfirming.asStateFlow()

    fun confirmItem(id: Long) {
        viewModelScope.launch {
            val record = notificationRepository.findById(id) ?: return@launch
            if (record.status == "confirmed") {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
                return@launch
            }
            insertTransaction(
                recordId = id,
                type = record.parsedType ?: "expense",
                amountCents = record.parsedAmount ?: 0,
                description = record.parsedDescription,
                packageName = record.packageName,
            )
            _uiEvent.send(UiEvent.ShowToast("已确认"))
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
            val record = notificationRepository.findById(id) ?: return@launch
            if (record.status == "confirmed") {
                _uiEvent.send(UiEvent.ShowToast("该笔已确认，请勿重复操作"))
                return@launch
            }
            insertTransaction(
                recordId = id,
                type = type,
                amountCents = amountCents,
                description = description.ifBlank { record.parsedDescription },
                packageName = record.packageName,
                categoryId = categoryId,
                tags = tags,
            )
            val learnDesc = description.ifBlank { record.parsedDescription }
            if (categoryId != null && !learnDesc.isNullOrBlank()) {
                categoryLearningEngine.learnFromCorrection(learnDesc.trim(), categoryId)
            }
            _uiEvent.send(UiEvent.ShowToast("已记账 ✓"))
        }
    }

    private suspend fun insertTransaction(
        recordId: Long,
        type: String,
        amountCents: Int,
        description: String?,
        packageName: String,
        categoryId: Int? = null,
        tags: List<String> = emptyList(),
    ) {
        val clientId = UUID.randomUUID().toString()
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val typeKey = if (type == "income") "income" else "expense"
        val category = categoryId?.let { id ->
            _categoriesByType.value[typeKey]?.firstOrNull { it.id == id }
        }

        val pendingTransaction = PendingTransactionEntity(
            clientId = clientId,
            type = type,
            amount = amountCents,
            categoryId = categoryId,
            categoryName = category?.name,
            categoryIcon = category?.icon,
            description = description,
            date = dateFormat.format(now),
            time = timeFormat.format(now),
            tags = if (tags.isNotEmpty()) tags.joinToString(",") else null,
            source = "app_notification",
            sourceDetail = NotificationSourceMapping.friendlyName(packageName),
            clientCreatedAt = now.toInstant().toString(),
        )

        pendingTransactionRepository.insert(pendingTransaction)
        notificationRepository.updateStatus(recordId, "confirmed", clientId)

        SyncScheduler.scheduleSyncIfNeeded(appContext)
        WidgetDataUpdater.notifyTransactionAdded(
            context = appContext,
            type = TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
            amountCents = amountCents,
            date = pendingTransaction.date,
        )
    }

    fun ignoreItem(id: Long) {
        viewModelScope.launch {
            notificationRepository.updateStatus(id, "ignored")
            _uiEvent.send(UiEvent.ShowToast("已忽略"))
        }
    }

    fun confirmAll() {
        viewModelScope.launch {
            _isConfirming.value = true
            try {
                val items = pendingNotifications.value
                var confirmed = 0
                var skipped = 0
                items.forEach { item ->
                    val record = notificationRepository.findById(item.id) ?: return@forEach

                    // 跳过未识别出有效金额的记录，避免生成 0 元账单
                    val amount = record.parsedAmount ?: 0
                    if (amount <= 0) {
                        skipped++
                        return@forEach
                    }

                    val clientId = UUID.randomUUID().toString()
                    val now = Date()
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                    val pendingTransaction = PendingTransactionEntity(
                        clientId = clientId,
                        type = record.parsedType ?: "expense",
                        amount = amount,
                        description = record.parsedDescription,
                        date = dateFormat.format(now),
                        time = timeFormat.format(now),
                        source = "app_notification",
                        sourceDetail = NotificationSourceMapping.friendlyName(record.packageName),
                        clientCreatedAt = now.toInstant().toString(),
                    )

                    pendingTransactionRepository.insert(pendingTransaction)
                    notificationRepository.updateStatus(item.id, "confirmed", clientId)
                    WidgetDataUpdater.notifyTransactionAdded(
                        context = appContext,
                        type = TransactionType.fromValue(record.parsedType ?: "expense") ?: TransactionType.EXPENSE,
                        amountCents = amount,
                        date = pendingTransaction.date,
                    )
                    confirmed++
                }

                SyncScheduler.scheduleSyncIfNeeded(appContext)

                val msg = when {
                    confirmed == 0 && skipped > 0 -> "无可自动确认的记录，$skipped 条需手动填写金额"
                    skipped > 0 -> "已确认 $confirmed 条，$skipped 条需手动填写金额"
                    else -> "已确认 $confirmed 条通知"
                }
                _uiEvent.send(UiEvent.ShowToast(msg))
            } finally {
                _isConfirming.value = false
            }
        }
    }
}
