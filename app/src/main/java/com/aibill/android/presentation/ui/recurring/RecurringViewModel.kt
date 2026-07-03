package com.aibill.android.presentation.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.RecurringDao
import com.aibill.android.data.local.entity.RecurringRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringDao: RecurringDao
) : ViewModel() {

    val rules: StateFlow<List<RecurringRuleEntity>> = recurringDao.observeAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addRule(
        name: String,
        type: String,
        amount: Int,
        categoryId: Int?,
        accountId: Int?,
        description: String?,
        dayOfMonth: Int
    ) {
        viewModelScope.launch {
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
        }
    }

    fun toggleEnabled(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            recurringDao.setEnabled(rule.id, !rule.isEnabled)
        }
    }

    fun deleteRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            recurringDao.deleteById(rule.id)
        }
    }
}
