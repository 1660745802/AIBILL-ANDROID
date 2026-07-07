package com.aibill.android.presentation.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.api.AuthApi
import com.aibill.android.data.remote.api.SettingsApi
import com.aibill.android.data.remote.safeApiCall
import com.aibill.android.domain.model.Result
import com.aibill.android.service.QuickEntryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsApi: SettingsApi,
    private val authApi: AuthApi,
    private val userPreferences: UserPreferences,
    private val appLogger: com.aibill.android.util.AppLogger,
) : ViewModel() {

    data class UiState(
        val themeMode: String = "system",
        val serverUrl: String = "",
        val notificationListenerGranted: Boolean = false,
        val hideFromRecents: Boolean = false,
        val notificationPrivacy: Boolean = false,
        val appLockEnabled: Boolean = false,
        val quickEntryEnabled: Boolean = false,
        val isLoading: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val theme = userPreferences.themeMode.first()
            val url = userPreferences.serverUrl.first().orEmpty()
            val hide = userPreferences.hideFromRecents.first()
            val privacy = userPreferences.notificationPrivacy.first()
            val appLock = userPreferences.appLockEnabled.first()
            val quickEntry = userPreferences.quickEntryEnabled.first()
            _uiState.update {
                it.copy(
                    themeMode = theme,
                    serverUrl = url,
                    hideFromRecents = hide,
                    notificationPrivacy = privacy,
                    appLockEnabled = appLock,
                    quickEntryEnabled = quickEntry
                )
            }
        }
    }

    fun onThemeChanged(mode: String) {
        viewModelScope.launch {
            userPreferences.setThemeMode(mode)
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    fun onHideFromRecentsChanged(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setHideFromRecents(enabled)
            _uiState.update { it.copy(hideFromRecents = enabled) }
        }
    }

    fun checkNotificationListenerPermission(context: Context) {
        val enabledListeners = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val granted = enabledListeners.contains(context.packageName)
        _uiState.update { it.copy(notificationListenerGranted = granted) }
    }

    fun onNotificationPrivacyChanged(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setNotificationPrivacy(enabled)
            _uiState.update { it.copy(notificationPrivacy = enabled) }
        }
    }

    fun onAppLockChanged(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setAppLockEnabled(enabled)
            _uiState.update { it.copy(appLockEnabled = enabled) }
        }
    }

    fun onQuickEntryChanged(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            userPreferences.setQuickEntryEnabled(enabled)
            _uiState.update { it.copy(quickEntryEnabled = enabled) }
            if (enabled) {
                QuickEntryService.start(context)
            } else {
                QuickEntryService.stop(context)
            }
        }
    }

    fun onChangePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = safeApiCall {
                authApi.changePassword(
                    mapOf("old_password" to oldPassword, "new_password" to newPassword)
                )
            }
            _uiState.update { it.copy(isLoading = false) }
            when (result) {
                is Result.Success -> _events.send("密码修改成功")
                is Result.Error -> _events.send(result.message)
                else -> {}
            }
        }
    }

    fun onExportLogs(context: Context) {
        viewModelScope.launch {
            try {
                val logText = appLogger.exportAsText()
                // 生成文件到 cache 目录（可清理）
                val logFile = java.io.File(context.cacheDir, "aibill_log_${System.currentTimeMillis()}.txt")
                logFile.writeText(logText)
                // 通过 FileProvider 分享
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", logFile
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "分享日志文件"))
            } catch (e: Exception) {
                _events.send("日志导出失败: ${e.message}")
            }
        }
    }
}
