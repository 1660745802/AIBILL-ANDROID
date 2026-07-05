package com.aibill.android.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.AutoRuleDao
import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.AutoRuleEntity
import com.aibill.android.data.local.entity.CategoryRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoRulesViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val categoryRuleDao: CategoryRuleDao,
    private val autoRuleDao: AutoRuleDao,
) : ViewModel() {

    data class UiState(
        val automationLevel: String = "standard",
        val learnedRules: List<CategoryRuleEntity> = emptyList(),
        val smallAmountEnabled: Boolean = false,
        val smallAmountThreshold: Int = 1000,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val level = userPreferences.automationLevel.first()
            val threshold = userPreferences.smallAmountThreshold.first()
            val rules = categoryRuleDao.getAll()
            val smallAmountRule = autoRuleDao.findRule("small_amount", threshold.toString())

            _uiState.update {
                it.copy(
                    automationLevel = level,
                    learnedRules = rules,
                    smallAmountEnabled = smallAmountRule?.isEnabled ?: false,
                    smallAmountThreshold = threshold
                )
            }
        }
    }

    fun onAutomationLevelChanged(level: String) {
        viewModelScope.launch {
            userPreferences.setAutomationLevel(level)
            _uiState.update { it.copy(automationLevel = level) }
        }
    }

    fun onSmallAmountToggle(enabled: Boolean) {
        viewModelScope.launch {
            val threshold = _uiState.value.smallAmountThreshold
            val existing = autoRuleDao.findRule("small_amount", threshold.toString())
            if (existing != null) {
                autoRuleDao.setEnabled(existing.id, enabled)
            } else {
                autoRuleDao.insert(
                    AutoRuleEntity(
                        ruleType = "small_amount",
                        value = threshold.toString(),
                        isEnabled = enabled
                    )
                )
            }
            _uiState.update { it.copy(smallAmountEnabled = enabled) }
        }
    }

    fun onThresholdChanged(cents: Int) {
        viewModelScope.launch {
            userPreferences.setSmallAmountThreshold(cents)
            // 关键修复：清掉所有 small_amount 旧规则（避免不同阈值的脏数据累积），
            // 然后按当前 enabled 状态重建一条匹配新阈值的规则。
            autoRuleDao.deleteByType("small_amount")
            val enabled = _uiState.value.smallAmountEnabled
            if (enabled) {
                autoRuleDao.insert(
                    AutoRuleEntity(
                        ruleType = "small_amount",
                        value = cents.toString(),
                        isEnabled = true
                    )
                )
            }
            _uiState.update { it.copy(smallAmountThreshold = cents) }
        }
    }

    fun onDeleteRule(keyword: String) {
        viewModelScope.launch {
            categoryRuleDao.deleteByKeyword(keyword)
            _uiState.update { state ->
                state.copy(learnedRules = state.learnedRules.filter { it.keyword != keyword })
            }
        }
    }
}
