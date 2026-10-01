package com.aibill.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aibill.android.di.ApplicationScope
import com.aibill.android.service.A11yHealthCheckWorker
import com.aibill.android.service.NlsHealthCheckWorker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.RulesSyncWorker
import com.aibill.android.service.UpdateCheckWorker
import com.aibill.android.util.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AiBillApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationRulesManager: NotificationRulesManager

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /**
     * 注入以启动网络监听（注册 NetworkCallback）。
     *
     * NetworkMonitor 是 @Singleton，注册逻辑全在它的 init 块里——**不注入就永不运行**，
     * 离线记账也就永远不会自动同步（历史上就是这个坑：类写好了但没人引用）。
     * 字段本身不被读取，保留 `@Suppress` 外的显式命名以便搜索定位。
     */
    @Inject
    lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        initTimber()
        scheduleWorkers()
        fetchNotificationRules()
        // 触发 NetworkMonitor 构造 → 注册 NetworkCallback → 联网即自动同步离线队列。
        // registerNetworkCallback 会立即回调当前网络状态，所以「已联网启动」也能排一次同步。
        Timber.d("NetworkMonitor 已启动, isOnline=${networkMonitor.isOnline.value}")
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun scheduleWorkers() {
        NlsHealthCheckWorker.schedule(this)
        A11yHealthCheckWorker.schedule(this)
        RulesSyncWorker.schedule(this)
        UpdateCheckWorker.schedule(this)
    }

    private fun fetchNotificationRules() {
        applicationScope.launch {
            notificationRulesManager.fetchRules()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
