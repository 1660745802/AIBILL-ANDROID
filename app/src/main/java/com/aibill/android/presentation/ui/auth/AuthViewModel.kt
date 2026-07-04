package com.aibill.android.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val isLoggedIn: Boolean = false
    )

    sealed class UiEvent {
        data object NavigateToHome : UiEvent()
        data class ShowError(val message: String) : UiEvent()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        // PR #59：之前在 init{} 同步调 authRepository.isLoggedIn() →
        // tokenManager.hasToken() → EncryptedSharedPreferences 同步磁盘读
        // 首次启动还需生成 master key，主线程 ANR 风险。
        // 改为 viewModelScope.launch 异步读取
        viewModelScope.launch {
            val hasToken = authRepository.isLoggedIn()
            _uiState.update { it.copy(isLoggedIn = hasToken) }
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(error = "用户名和密码不能为空") }
            viewModelScope.launch {
                _uiEvent.send(UiEvent.ShowError("用户名和密码不能为空"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = authRepository.login(username, password)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    _uiEvent.send(UiEvent.NavigateToHome)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                    _uiEvent.send(UiEvent.ShowError(result.message))
                }
                is Result.Loading -> {
                    // no-op
                }
            }
        }
    }

    fun register(username: String, password: String, inviteCode: String, nickname: String?) {
        if (username.isBlank() || password.isBlank() || inviteCode.isBlank()) {
            _uiState.update { it.copy(error = "用户名、密码和邀请码不能为空") }
            viewModelScope.launch {
                _uiEvent.send(UiEvent.ShowError("用户名、密码和邀请码不能为空"))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = authRepository.register(username, password, inviteCode, nickname)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                    _uiEvent.send(UiEvent.NavigateToHome)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                    _uiEvent.send(UiEvent.ShowError(result.message))
                }
                is Result.Loading -> {
                    // no-op
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
