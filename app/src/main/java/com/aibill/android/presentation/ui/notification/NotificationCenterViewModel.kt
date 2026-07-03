package com.aibill.android.presentation.ui.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
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

            val clientId = UUID.randomUUID().toString()
            val now = Date()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            val pendingTransaction = PendingTransactionEntity(
                clientId = clientId,
                type = record.parsedType ?: "expense",
                amount = record.parsedAmount ?: 0,
                description = record.parsedDescription,
                date = dateFormat.format(now),
                time = timeFormat.format(now),
                source = "app_notification",
                sourceDetail = record.packageName,
                clientCreatedAt = now.toInstant().toString()
            )

            pendingTransactionDao.insert(pendingTransaction)
            notificationRecordDao.updateStatus(id, "confirmed", clientId)

            SyncScheduler.scheduleSyncIfNeeded(appContext)
            WidgetDataUpdater.notifyTransactionAdded(appContext)

            _uiEvent.send(UiEvent.ShowToast("已确认"))
        }
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
            val count = items.size
            items.forEach { item ->
                val record = notificationRecordDao.findById(item.id) ?: return@forEach

                val clientId = UUID.randomUUID().toString()
                val now = Date()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                val pendingTransaction = PendingTransactionEntity(
                    clientId = clientId,
                    type = record.parsedType ?: "expense",
                    amount = record.parsedAmount ?: 0,
                    description = record.parsedDescription,
                    date = dateFormat.format(now),
                    time = timeFormat.format(now),
                    source = "app_notification",
                    sourceDetail = record.packageName,
                    clientCreatedAt = now.toInstant().toString()
                )

                pendingTransactionDao.insert(pendingTransaction)
                notificationRecordDao.updateStatus(item.id, "confirmed", clientId)
            }

            SyncScheduler.scheduleSyncIfNeeded(appContext)
            WidgetDataUpdater.notifyTransactionAdded(appContext)

            _uiEvent.send(UiEvent.ShowToast("已确认 $count 条通知"))
        }
    }
}
