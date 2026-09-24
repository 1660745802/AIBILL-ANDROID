package com.aibill.android.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.AuthRepository
import com.aibill.android.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我的」页 ViewModel。
 *
 * 暴露：
 * - [displayName]   : 昵称（昵称 → 用户名 → "用户" 兜底）
 * - [username]      : 用户名
 * - [totalTransactions] : 累计记账笔数（**唯一成就指标**，避免和首页/统计冲突）
 * - [trashCount]    : 回收站条目数（用于列表 Badge）
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val transactionRepository: TransactionRepository,
    userPreferences: UserPreferences,
) : ViewModel() {

    val displayName: StateFlow<String> = userPreferences.nickname
        .combine(userPreferences.username) { nickname, username ->
            nickname?.takeIf { it.isNotBlank() }
                ?: username?.takeIf { it.isNotBlank() }
                ?: "用户"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "用户")

    val username: StateFlow<String> = userPreferences.username
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 累计记账笔数（首次加载从网络拉，之后调用方按需 refresh） */
    private val _totalTransactions = kotlinx.coroutines.flow.MutableStateFlow(0)
    val totalTransactions: StateFlow<Int> = _totalTransactions

    private val _trashCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    init {
        refresh()
    }

    fun refresh() {
        loadTotal()
        loadTrashCount()
    }

    private fun loadTotal() {
        viewModelScope.launch {
            when (val r = transactionRepository.getTransactions(
                com.aibill.android.domain.repository.TransactionQuery(page = 1, pageSize = 1)
            )) {
                is Result.Success -> _totalTransactions.value = r.data.total
                else -> Unit
            }
        }
    }

    private fun loadTrashCount() {
        viewModelScope.launch {
            when (val r = transactionRepository.getTrash()) {
                is Result.Success -> _trashCount.value = r.data.size
                else -> Unit
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}
