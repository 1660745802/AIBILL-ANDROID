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
            val today = LocalDate.now()
            if (today.dayOfMonth in 1..3) {
                sendMonthlyReport(today)
            } else {
                sendWeeklyInsight(today)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "InsightWorker failed")
            Result.retry()
        }
    }

    /**
     * 周中推送：本月进度 + 日均 + 环比
     */
    private suspend fun sendWeeklyInsight(today: LocalDate) {
        val response = statsApi.getSummary(today.year, today.monthValue)
        if (response.code != 0 || response.data == null) return

        val data = response.data
        val daysElapsed = today.dayOfMonth
        val dailyAvg = if (daysElapsed > 0) data.expense / daysElapsed / 100.0 else 0.0
        val projectedYuan = (dailyAvg * today.lengthOfMonth()).toInt()

        val title = "📊 本周消费小结"
        val content = buildString {
            append("本月已支出 ¥${"%.0f".format(data.expense / 100.0)}")
            append("，日均 ¥${"%.0f".format(dailyAvg)}")
            data.expenseChange?.let { change ->
                if (change > 0) append("\n比上月同期多 ${change}%")
                else if (change < 0) append("\n比上月同期少 ${-change}%")
            }
            append("\n按当前节奏，本月预计支出 ¥$projectedYuan")
        }
        sendNotification(title, content)
    }

    /**
     * 月初推送：上月完整总结
     */
    private suspend fun sendMonthlyReport(today: LocalDate) {
        val lastMonth = today.minusMonths(1)
        val response = statsApi.getSummary(lastMonth.year, lastMonth.monthValue)
        if (response.code != 0 || response.data == null) return

        val data = response.data
        val title = "📝 ${lastMonth.monthValue}月账单总结"
        val content = buildString {
            append("支出 ¥${"%.0f".format(data.expense / 100.0)}")
            append(" | 收入 ¥${"%.0f".format(data.income / 100.0)}")
            append(" | 结余 ¥${"%.0f".format(data.balance / 100.0)}")
            data.expenseChange?.let { change ->
                append("\n")
                when {
                    change > 0 -> append("比${if (lastMonth.monthValue > 1) "${lastMonth.monthValue - 1}月" else "上月"}多花了 ${change}%")
                    change < 0 -> append("比${if (lastMonth.monthValue > 1) "${lastMonth.monthValue - 1}月" else "上月"}省了 ${-change}% 👍")
                    else -> append("与上月持平")
                }
            }
            val dailyAvg = data.expense / lastMonth.lengthOfMonth() / 100.0
            append("\n日均 ¥${"%.0f".format(dailyAvg)}")
        }
        sendNotification(title, content)
    }

    private fun sendNotification(title: String, content: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
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
