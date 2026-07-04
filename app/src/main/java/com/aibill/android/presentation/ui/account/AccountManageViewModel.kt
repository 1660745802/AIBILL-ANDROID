package com.aibill.android.presentation.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountManageViewModel @Inject constructor(
    // PR #61：删除 CategoryApi 直接依赖，全部走 AccountRepository
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> =
        accountRepository.observeAccounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun createAccount(
        name: String,
        type: String,
        icon: String,
        initialBalance: Int,
        sortOrder: Int
    ) {
        viewModelScope.launch {
            when (val result = accountRepository.createAccount(
                name = name, type = type, icon = icon,
                initialBalance = initialBalance, sortOrder = sortOrder,
            )) {
                is Result.Success -> _toastMessage.value = "创建成功"
                is Result.Error -> _toastMessage.value = "创建失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun updateAccount(id: Int, name: String, icon: String, initialBalance: Int) {
        viewModelScope.launch {
            when (val result = accountRepository.updateAccount(
                id = id, name = name, icon = icon, initialBalance = initialBalance,
            )) {
                is Result.Success -> _toastMessage.value = "更新成功"
                is Result.Error -> _toastMessage.value = "更新失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun deleteAccount(id: Int) {
        viewModelScope.launch {
            when (val result = accountRepository.deleteAccount(id)) {
                is Result.Success -> _toastMessage.value = "已删除"
                is Result.Error -> _toastMessage.value = "删除失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun clearToast() {
        _toastMessage.update { null }
    }
}