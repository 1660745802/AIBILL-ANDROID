package com.aibill.android.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userPreferences: UserPreferences,
) : ViewModel() {
    // PR #51：头像读 nickname/username 实际值（之前写死"用户"）
    val displayName: StateFlow<String> = userPreferences.nickname
        .combine(userPreferences.username) { nickname, username ->
            nickname?.takeIf { it.isNotBlank() }
                ?: username?.takeIf { it.isNotBlank() }
                ?: "用户"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "用户")

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}