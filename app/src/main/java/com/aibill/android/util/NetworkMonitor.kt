package com.aibill.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络状态监听：把「当前有没有网」维持成一个 [StateFlow]。
 *
 * 记账**不在网络跳变时做任何补偿**——没有网时记账当场被拒绝
 * （见 `docs/ARCHITECTURE.md` §2.3），所以本类不再是任何记账路径的触发点，
 * 它只是给需要「此刻是否在线」的调用方一个可信读数的观察者。
 *
 * ⚠️ **必须在本进程内被注入一次，否则本类完全不会运行。**
 * 它是 `@Singleton`，所有注册都发生在 `init` 里——没有任何人注入 = 永远不会构造 =
 * NetworkCallback 不会注册 = `isOnline` 永远停在构造时的初值。
 * 目前唯一的注入点是 [com.aibill.android.AiBillApp]（Application 启动时）。
 * 移除那处注入会静默回归，请勿删除。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
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

    /** 在线状态的唯一写入口；`MutableStateFlow` 对同值写入不发射，重复回调无副作用。 */
    private fun onOnlineStateChanged(nowOnline: Boolean) {
        _isOnline.value = nowOnline
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NET_CAPABILITY_INTERNET)
            .build()
        // registerNetworkCallback 会**立即**为当前网络回调一次 onAvailable/onCapabilitiesChanged，
        // 因此 `isOnline` 在 App 启动后很短时间内就会收敛到真实值。
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
