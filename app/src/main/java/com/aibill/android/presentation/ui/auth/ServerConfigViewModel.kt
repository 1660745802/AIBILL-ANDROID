package com.aibill.android.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.LocalDataOwnership
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
    val isSaving: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
)

/**
 * 服务器配置 ViewModel。**已重构**（两次）：
 * 移除 3 个 DAO 直接依赖 → 走 Repository（候选 3）；
 * 再移除 3 个 Repository 的 `deleteAll` → 走本地数据归属 module（候选 4）。
 *
 * 只留两个依赖：
 * - [localDataOwnership]：`onSave` 的处置——本 ViewModel 只说「换服务器」这一句意图；
 * - [userPreferences]：DataStore facade，按项目惯例允许直接使用。
 *
 * 换服务器前**没有**「未同步数据」二次确认：不存在「未同步」这个状态，
 * 本地缓存是服务端派生数据，换命名空间就该整份丢掉（见 `docs/ARCHITECTURE.md` §6.3）。
 */
@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val localDataOwnership: LocalDataOwnership,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = userPreferences.serverUrl.first()
            _uiState.update { it.copy(serverUrl = savedUrl.orEmpty()) }
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
                // use {} 必须包住 Response：不关闭会泄漏连接（OkHttp 连接池无法复用）
                val code = client.newCall(request).execute().use { response -> response.code }

                // 只要能连通（包括 401 未认证也算连通）
                if (code in 200..499) {
                    _uiState.update { it.copy(isTesting = false, isConnected = true) }
                } else {
                    _uiState.update { it.copy(isTesting = false, error = "服务器响应异常: $code") }
                }
            } catch (e: Exception) {
                Timber.e(e, "连接测试失败")
                _uiState.update { it.copy(isTesting = false, error = "无法连接到服务器，请检查地址") }
            }
        }
    }

    /**
     * 保存服务器地址；`clearLocalCache` 为 true 时按「换服务器」处置本地数据。
     *
     * 事实性说明（**不是**可选入口）：UI 的唯一调用点固定传 `true`，
     * 当前**没有**任何调用方传 `false`。参数保留是为了让「首配置/不希望清空」
     * 的语义在签名上仍有表达力；新增调用方若要传 `false`，
     * 必须先想清楚它与清空时机的配合，不要默认复用。
     *
     * 「清哪几张表」不再由本 ViewModel 决定（架构候选 4）：[DisposalIntent.SwitchingServer]
     * 由本地数据归属 module 兑现，清空范围 = pending/categories/accounts/notification_records
     * 四张，与登录路径换号时**同一份**清单。收口前这里是手列的第二份。
     *
     * 代价（刻意接受）：A→B→A 换回原服务器时，中间捕获的通知记录一并丢失——通知记录是
     * **设备侧**捕获的审计数据，不带服务器归属，按「换服务器 = 换命名空间」处理。
     * `ServerConfig` 仅从登录页可达，故不存在「用户在线时被误清」。
     */
    fun onSave(clearLocalCache: Boolean = true, onSaved: () -> Unit = {}) {
        if (_uiState.value.isSaving) return
        val url = normalizeUrl(_uiState.value.serverUrl)
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val previousUrl = userPreferences.serverUrl.first()
                // 先落 URL：写失败时绝不能再清本地数据。
                userPreferences.setServerUrl(url)
                if (clearLocalCache) {
                    try {
                        localDataOwnership.dispose(DisposalIntent.SwitchingServer)
                    } catch (e: Exception) {
                        // 清理失败则回滚地址，避免旧服务器缓存被新服务器会话读取。
                        runCatching { userPreferences.setServerUrl(previousUrl ?: "") }
                        throw e
                    }
                }
                onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "服务器配置保存失败")
                _uiState.update { it.copy(error = "保存失败，请重试") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

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
