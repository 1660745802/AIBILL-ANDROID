package com.aibill.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aibill.android.service.BudgetCheckWorker
import com.aibill.android.service.InsightWorker
import com.aibill.android.service.NlsHealthCheckWorker
import com.aibill.android.service.RecurringWorker
import com.aibill.android.util.NetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AiBillApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        initTimber()
        // 引用 networkMonitor 以触发 Hilt 实例化 NetworkMonitor，
        // 其 init 块会注册 ConnectivityManager.NetworkCallback，
        // 联网恢复时自动调用 SyncScheduler.scheduleSyncIfNeeded
        networkMonitor.isOnline
        scheduleWorkers()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun scheduleWorkers() {
        BudgetCheckWorker.schedule(this)
        InsightWorker.schedule(this)
        RecurringWorker.schedule(this)
        NlsHealthCheckWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
