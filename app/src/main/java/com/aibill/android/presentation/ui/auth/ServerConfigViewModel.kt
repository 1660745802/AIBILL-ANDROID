package com.aibill.android.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.PendingTransactionRepository
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
    /** 待同步离线交易数量，UI 用于切换服务器前提示 */
    val pendingCount: Int = 0,
)

/**
 * 服务器配置 ViewModel。**已重构**：移除 3 个 DAO 直接依赖，全部走 Repository。
 *
 * 保留 UserPreferences（DataStore facade，按项目惯例允许直接使用）。
 */
@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val pendingTransactionRepository: PendingTransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = userPreferences.serverUrl.first()
            val count = pendingTransactionRepository.getPendingCount()
            _uiState.update {
                it.copy(
                    serverUrl = savedUrl.orEmpty(),
                    pendingCount = count,
                )
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

    /**
     * @param clearLocalCache true 清空 pending/categories/accounts；首次配置可传 false。
     */
    fun onSave(clearLocalCache: Boolean = true) {
        viewModelScope.launch {
            val url = normalizeUrl(_uiState.value.serverUrl)
            if (clearLocalCache) {
                pendingTransactionRepository.deleteAll()
                categoryRepository.deleteAll()
                accountRepository.deleteAll()
                _uiState.update { it.copy(pendingCount = 0) }
            }
            userPreferences.setServerUrl(url)
        }
    }

    fun hasPendingData(): Boolean = _uiState.value.pendingCount > 0

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
