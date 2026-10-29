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
import kotlinx.coroutines.flow.catch
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

    /** null 表示 DataStore 尚未加载；未知不是「未启用」，不能展示账本。 */
    private val _appLockEnabled = MutableStateFlow<Boolean?>(null)
    val appLockEnabled: StateFlow<Boolean?> = _appLockEnabled.asStateFlow()

    // 解锁状态独立于偏好：重新订阅/重组/重复偏好发射都不能撤销本次解锁。
    private val _isLocked = MutableStateFlow<Boolean?>(null)
    val isLocked: StateFlow<Boolean?> = _isLocked.asStateFlow()

    // 读取失败按“已启用”处理（fail-closed），不能把损坏的偏好当成“未上锁”或无限 Loading。
    private val lockPreference: kotlinx.coroutines.flow.Flow<Boolean> =
        userPreferences.appLockEnabled.catch {
            _appLockEnabled.value = true
            _isLocked.value = true
            emit(true)
        }

    // 后台标记放在 ViewModel：配置变更重建 Activity 时仍能识别“从后台返回”。
    private var hostStopped = false

    fun onUnlocked() {
        if (_isLocked.value == true) _isLocked.value = false
    }

    fun onAppForegrounded() {
        _isLocked.value = _appLockEnabled.value
    }

    fun onHostStopped(changingConfigurations: Boolean) {
        if (!changingConfigurations) hostStopped = true
    }

    fun onHostStarted() {
        if (hostStopped) {
            hostStopped = false
            onAppForegrounded()
        }
    }

    /** 主题模式：system / light / dark */
    val themeMode: StateFlow<String> = userPreferences.themeMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, "system",
    )

    /**
     * 是否启用 Material You 动态取色（Android 12+）。
     *
     * 初始值必须和 `UserPreferences.dynamicColorEnabled` 的默认值一致（false）：
     * 否则 DataStore 首次 emit 之前，UI 会先用 `true` 渲染一帧动态色再翻转成品牌色，
     * 冷启动时肉眼可见地闪一下。
     */
    val dynamicColorEnabled: StateFlow<Boolean> = userPreferences.dynamicColorEnabled.stateIn(
        viewModelScope, SharingStarted.Eagerly, false,
    )

    /** 是否从最近任务中隐藏 */
    val hideFromRecents: StateFlow<Boolean> = userPreferences.hideFromRecents.stateIn(
        viewModelScope, SharingStarted.Eagerly, false,
    )

    init {
        viewModelScope.launch {
            lockPreference.collect { enabled ->
                _appLockEnabled.value = enabled
                if (_isLocked.value == null) _isLocked.value = enabled
            }
        }
        viewModelScope.launch {
            lockPreference.first()
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
