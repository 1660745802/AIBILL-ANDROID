package com.aibill.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.aibill.android.service.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络状态监听：网络从「不可用 → 可用」时触发一次 [SyncScheduler]，
 * 让离线队列自动上传（README「离线可用：联网后 WorkManager 自动同步」的实现处）。
 *
 * ⚠️ **必须在本进程内被注入一次，否则本类完全不会运行。**
 * 它是 `@Singleton`，所有注册都发生在 `init` 里——没有任何人注入 = 永远不会构造 =
 * NetworkCallback 不会注册 = 离线记账永不自动同步。
 * 目前唯一的注入点是 [com.aibill.android.AiBillApp]（Application 启动时）。
 * 移除那处注入会静默回归，请勿删除。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(checkCurrentNetwork())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onOnlineStateChanged(true)
        }

        override fun onLost(network: Network) {
            onOnlineStateChanged(!hasActiveNetwork())
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            onOnlineStateChanged(
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            )
        }
    }

    /**
     * 统一的在线状态入口：**只在「不可用 → 可用」的跳变时**排一次同步。
     *
     * 之前 [onCapabilitiesChanged] 每次都排（计费切换 / WiFi↔蜂窝切换 / 网络能力刷新
     * 都会回调），配合 SyncScheduler 的 `APPEND_OR_REPLACE` 会让 unique work 链不断增长。
     */
    private fun onOnlineStateChanged(nowOnline: Boolean) {
        val wasOnline = _isOnline.value
        _isOnline.value = nowOnline
        if (nowOnline && !wasOnline) {
            SyncScheduler.scheduleSyncIfNeeded(context)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NET_CAPABILITY_INTERNET)
            .build()
        // registerNetworkCallback 会**立即**为当前网络回调一次 onAvailable/onCapabilitiesChanged，
        // 因此「App 启动时已联网」也能触发一次同步（覆盖进程被杀期间的积压）。
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun checkCurrentNetwork(): Boolean {
        return hasActiveNetwork()
    }

    private fun hasActiveNetwork(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return false
        return capabilities.hasCapability(NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val NET_CAPABILITY_INTERNET = NetworkCapabilities.NET_CAPABILITY_INTERNET
    }
}
