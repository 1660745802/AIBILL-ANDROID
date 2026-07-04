package com.aibill.android.presentation.ui.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.service.SyncScheduler
import com.aibill.android.service.WidgetDataUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val notificationRecordDao: NotificationRecordDao,
    private val pendingTransactionDao: PendingTransactionDao,
    private val transactionRepository: TransactionRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
    }

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    val pendingNotifications: StateFlow<List<NotificationRecordEntity>> =
        notificationRecordDao.observePending()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val pendingCount: StateFlow<Int> =
        notificationRecordDao.observePendingCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0
            )

    fun confirmItem(id: Long) {
        viewModelScope.launch {
            val record = notificationRecordDao.findById(id) ?: return@launch
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

    /**
     * 确认前编辑：用户在弹窗中修改金额/类型/描述后确认
     */
    fun confirmWithEdit(id: Long, type: String, amountCents: Int, description: String) {
        viewModelScope.launch {
            if (amountCents <= 0) {
                _uiEvent.send(UiEvent.ShowToast("请输入有效金额"))
                return@launch
            }
            val record = notificationRecordDao.findById(id) ?: return@launch
            insertTransaction(
                recordId = id,
                type = type,
                amountCents = amountCents,
                description = description.ifBlank { record.parsedDescription },
                packageName = record.packageName,
            )
            _uiEvent.send(UiEvent.ShowToast("已记账 ✓"))
        }
    }

    private suspend fun insertTransaction(
        recordId: Long,
        type: String,
        amountCents: Int,
        description: String?,
        packageName: String,
    ) {
        val clientId = UUID.randomUUID().toString()
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        val pendingTransaction = PendingTransactionEntity(
            clientId = clientId,
            type = type,
            amount = amountCents,
            description = description,
            date = dateFormat.format(now),
            time = timeFormat.format(now),
            source = "app_notification",
            sourceDetail = com.aibill.android.util.NotificationSourceMapping.friendlyName(packageName),
            clientCreatedAt = now.toInstant().toString()
        )

        pendingTransactionDao.insert(pendingTransaction)
        notificationRecordDao.updateStatus(recordId, "confirmed", clientId)

        SyncScheduler.scheduleSyncIfNeeded(appContext)
        WidgetDataUpdater.notifyTransactionAdded(
            context = appContext,
            type = com.aibill.android.domain.model.TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
            amountCents = amountCents,
            date = pendingTransaction.date,
        )
    }

    fun ignoreItem(id: Long) {
        viewModelScope.launch {
            notificationRecordDao.updateStatus(id, "ignored")
            _uiEvent.send(UiEvent.ShowToast("已忽略"))
        }
    }

    fun confirmAll() {
        viewModelScope.launch {
            val items = pendingNotifications.value
            var confirmed = 0
            var skipped = 0
            items.forEach { item ->
                val record = notificationRecordDao.findById(item.id) ?: return@forEach

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
                    sourceDetail = com.aibill.android.util.NotificationSourceMapping.friendlyName(record.packageName),
                    clientCreatedAt = now.toInstant().toString()
                )

                pendingTransactionDao.insert(pendingTransaction)
                notificationRecordDao.updateStatus(item.id, "confirmed", clientId)
                WidgetDataUpdater.notifyTransactionAdded(
                    context = appContext,
                    type = com.aibill.android.domain.model.TransactionType.fromValue(record.parsedType ?: "expense") ?: TransactionType.EXPENSE,
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
        }
    }
}
