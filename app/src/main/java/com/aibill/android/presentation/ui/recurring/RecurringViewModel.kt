package com.aibill.android.presentation.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.RecurringDao
import com.aibill.android.data.local.entity.RecurringRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringDao: RecurringDao
) : ViewModel() {

    val rules: StateFlow<List<RecurringRuleEntity>> = recurringDao.observeAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // PR L6：UiEvent 通道，错误时通知 UI
    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
    }

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun addRule(
        name: String,
        type: String,
        amount: Int,
        categoryId: Int?,
        accountId: Int?,
        description: String?,
        dayOfMonth: Int
    ) {
        // PR L6：加错误路径
        viewModelScope.launch {
            try {
                recurringDao.insert(
                    RecurringRuleEntity(
                        name = name,
                        type = type,
                        amount = amount,
                        categoryId = categoryId,
                        accountId = accountId,
                        description = description,
                        dayOfMonth = dayOfMonth
                    )
                )
                _uiEvent.send(UiEvent.ShowToast("规则已添加"))
            } catch (e: Exception) {
                _uiEvent.send(UiEvent.ShowToast("添加失败: ${e.message}"))
            }
        }
    }

    fun toggleEnabled(rule: RecurringRuleEntity) {
        // PR L6：加错误路径，Room 抛异常时不让协程静默死
        viewModelScope.launch {
            try {
                recurringDao.setEnabled(rule.id, !rule.isEnabled)
            } catch (e: Exception) {
                _uiEvent.send(UiEvent.ShowToast("切换失败: ${e.message}"))
            }
        }
    }

    fun deleteRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            try {
                recurringDao.deleteById(rule.id)
                _uiEvent.send(UiEvent.ShowToast("已删除「${rule.name}」"))
            } catch (e: Exception) {
                _uiEvent.send(UiEvent.ShowToast("删除失败: ${e.message}"))
            }
        }
    }
}
