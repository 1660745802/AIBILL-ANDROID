package com.aibill.android.presentation.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.domain.model.Account
import com.aibill.android.domain.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AccountManageViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val categoryApi: CategoryApi,
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
            try {
                val request = mapOf<String, Any>(
                    "name" to name,
                    "type" to type,
                    "icon" to icon,
                    "initial_balance" to initialBalance,
                    "sort_order" to sortOrder,
                )
                val response = categoryApi.createAccount(request)
                if (response.code == 0) {
                    _toastMessage.value = "创建成功"
                    accountRepository.syncAccounts()
                } else {
                    _toastMessage.value = "创建失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "创建账户失败")
                _toastMessage.value = "创建失败: ${e.message}"
            }
        }
    }

    fun updateAccount(id: Int, name: String, icon: String, initialBalance: Int) {
        viewModelScope.launch {
            try {
                val request = mapOf<String, Any>(
                    "name" to name,
                    "icon" to icon,
                    "initial_balance" to initialBalance,
                )
                val response = categoryApi.updateAccount(id, request)
                if (response.code == 0) {
                    _toastMessage.value = "更新成功"
                    accountRepository.syncAccounts()
                } else {
                    _toastMessage.value = "更新失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "更新账户失败")
                _toastMessage.value = "更新失败: ${e.message}"
            }
        }
    }

    fun deleteAccount(id: Int) {
        viewModelScope.launch {
            try {
                val response = categoryApi.deleteAccount(id)
                if (response.code == 0) {
                    _toastMessage.value = "已停用"
                    accountRepository.syncAccounts()
                } else {
                    _toastMessage.value = "停用失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "停用账户失败")
                _toastMessage.value = "停用失败: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toastMessage.update { null }
    }
}
