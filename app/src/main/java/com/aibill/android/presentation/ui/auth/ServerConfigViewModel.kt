package com.aibill.android.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ServerConfigUiState(
    val serverUrl: String = "",
    val isTesting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    init {
        // 加载已保存的服务器地址
        viewModelScope.launch {
            val savedUrl = userPreferences.serverUrl.first()
            if (!savedUrl.isNullOrBlank()) {
                _uiState.update { it.copy(serverUrl = savedUrl) }
            }
        }
    }

    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, isConnected = false, error = null) }
    }

    fun onTestConnection() {
        val url = normalizeUrl(_uiState.value.serverUrl)
        _uiState.update { it.copy(serverUrl = url, isTesting = true, error = null, isConnected = false) }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val testUrl = "${url}/auth/me"
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(testUrl).get().build()
                val response = client.newCall(request).execute()

                // 只要能连通（包括 401 未认证也算连通）
                if (response.code in 200..499) {
                    _uiState.update { it.copy(isTesting = false, isConnected = true) }
                } else {
                    _uiState.update { it.copy(isTesting = false, error = "服务器响应异常: ${response.code}") }
                }
            } catch (e: Exception) {
                Timber.e(e, "连接测试失败")
                _uiState.update { it.copy(isTesting = false, error = "无法连接到服务器，请检查地址") }
            }
        }
    }

    fun onSave() {
        viewModelScope.launch {
            val url = normalizeUrl(_uiState.value.serverUrl)
            userPreferences.setServerUrl(url)
        }
    }

    /**
     * 标准化 URL：自动补全协议和 API 路径
     */
    private fun normalizeUrl(input: String): String {
        var url = input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/api") && !url.endsWith("/api/")) {
            url = url.trimEnd('/') + "/api"
        }
        return url.trimEnd('/')
    }
}
