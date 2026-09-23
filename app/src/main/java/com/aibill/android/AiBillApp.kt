package com.aibill.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aibill.android.di.ApplicationScope
import com.aibill.android.service.A11yHealthCheckWorker
import com.aibill.android.service.InsightWorker
import com.aibill.android.service.NlsHealthCheckWorker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.RulesSyncWorker
import com.aibill.android.service.UpdateCheckWorker
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

    override fun onCreate() {
        super.onCreate()
        initTimber()
        scheduleWorkers()
        fetchNotificationRules()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun scheduleWorkers() {
        InsightWorker.schedule(this)
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
