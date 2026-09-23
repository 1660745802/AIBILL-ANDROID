package com.aibill.android.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.presentation.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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

    /**
     * PR 修复 AppLock 冷启动竞态：appLockEnabled 提升为 StateFlow，
     * 初始值 Eagerly 同步消费默认值 false，后续 DataStore 变化即时同步。
     * MainActivity onCreate 可同步读 `.value` 避免异步 race。
     */
    val appLockEnabled: StateFlow<Boolean> = userPreferences.appLockEnabled.stateIn(
        viewModelScope, SharingStarted.Eagerly, false,
    )

    /** 主题模式：system / light / dark */
    val themeMode: StateFlow<String> = userPreferences.themeMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, "system",
    )

    /** 是否启用 Material You 动态取色（Android 12+） */
    val dynamicColorEnabled: StateFlow<Boolean> = userPreferences.dynamicColorEnabled.stateIn(
        viewModelScope, SharingStarted.Eagerly, true,
    )

    /** 是否从最近任务中隐藏 */
    val hideFromRecents: StateFlow<Boolean> = userPreferences.hideFromRecents.stateIn(
        viewModelScope, SharingStarted.Eagerly, false,
    )

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
