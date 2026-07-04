package com.aibill.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.R
import com.aibill.android.data.remote.api.StatsApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@HiltWorker
class InsightWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val statsApi: StatsApi
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = LocalDate.now()
            val response = statsApi.getSummary(now.year, now.monthValue)
            if (response.code != 0 || response.data == null) {
                return Result.retry()
            }

            val expenseChange = response.data.expenseChange
            if (expenseChange != null && expenseChange > THRESHOLD_PERCENT) {
                val changeText = expenseChange.toString()
                sendNotification(
                    title = "💡 消费洞察",
                    content = "本月支出比上月多 $changeText%，注意控制哦"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "InsightWorker failed")
            Result.retry()
        }
    }

    private fun sendNotification(title: String, content: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每周消费趋势分析"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val WORK_NAME = "insight_work"
        private const val CHANNEL_ID = "aibill_insight"
        private const val CHANNEL_NAME = "消费洞察"
        private const val NOTIFICATION_ID = 60001
        private const val THRESHOLD_PERCENT = 30

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InsightWorker>(
                7, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
