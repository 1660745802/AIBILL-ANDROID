package com.aibill.android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    data class StartupState(
        val isReady: Boolean = false,
        val startRoute: Route = Route.ServerConfig,
    )

    private val _startupState = MutableStateFlow(StartupState())
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    init {
        viewModelScope.launch {
            val hasToken = tokenManager.hasToken()
            val hasServer = userPreferences.serverUrl.first()?.isNotBlank() == true
            val route = when {
                !hasServer -> Route.ServerConfig
                !hasToken -> Route.Login
                else -> Route.Home
            }
            _startupState.value = StartupState(isReady = true, startRoute = route)
        }
    }
}
