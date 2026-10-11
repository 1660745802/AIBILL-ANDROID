package com.aibill.android.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.NotificationRecordRepository
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
    /**
     * 未同步条数（含 failed），UI 用于切换服务器前的二次确认。
     *
     * **快照**：仅在 `init` 里取一次，之后不随队列变化刷新。
     * 口径见 [PendingTransactionRepository.getAnyUnsyncedCount]。
     */
    val unsyncedCount: Int = 0,
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
    private val notificationRecordRepository: NotificationRecordRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = userPreferences.serverUrl.first()
            val count = pendingTransactionRepository.getAnyUnsyncedCount()
            _uiState.update {
                it.copy(
                    serverUrl = savedUrl.orEmpty(),
                    unsyncedCount = count,
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
     * 保存服务器地址；`clearLocalCache` 为 true 时同时清空 pending/categories/accounts/notification_records。
     *
     * 事实性说明（**不是**可选入口）：UI 的两处调用点——直接保存与二次确认——
     * 都固定传 `true`，当前**没有**任何调用方传 `false`。参数保留是为了让
     * 「首配置/不希望清空」的语义在签名上仍有表达力；新增调用方若要传 `false`，
     * 必须先想清楚它与 [hasPendingData] 的弹框逻辑如何配合，不要默认复用。
     * 清空范围固定为 4 张表（pending/categories/accounts/notification_records），
     * 与 `AuthRepositoryImpl.clearLocalCache()` **逐表一致**；两边必须同步改，
     * 各自的测试分别钉住 4 表（`ServerConfigViewModelTest` / `AuthRepositoryImplTest`）。
     * 新增第 5 张表时两处都要动——已知单点，尚未抽公共 cleaner。
     *
     * G-3：历史上此处只清 3 张、通知记录会残留。现已补齐第 4 张，与 [AuthRepositoryImpl.clearLocalCache] 逐表一致。
     * 代价：A→B→A 换回原服务器时，中间捕获的通知记录一并丢失——通知记录是**设备侧**捕获
     * 的审计数据，不带服务器归属，按「换服务器 = 换命名空间」处理，接受该丢失。
     * `ServerConfig` 仅从登录页可达，故不存在「用户在线时被误清」。
     */
    fun onSave(clearLocalCache: Boolean = true) {
        viewModelScope.launch {
            val url = normalizeUrl(_uiState.value.serverUrl)
            if (clearLocalCache) {
                pendingTransactionRepository.deleteAll()
                categoryRepository.deleteAll()
                accountRepository.deleteAll()
                notificationRecordRepository.deleteAll()
                _uiState.update { it.copy(unsyncedCount = 0) }
            }
            userPreferences.setServerUrl(url)
        }
    }

    /** 是否存在「切换服务器时会被一并清空」的未同步数据（pending + failed）。 */
    fun hasPendingData(): Boolean = _uiState.value.unsyncedCount > 0

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
