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
import com.aibill.android.data.remote.api.BudgetApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@HiltWorker
class BudgetCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val budgetApi: BudgetApi
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = LocalDate.now()
            val response = budgetApi.getBudgets(now.year, now.monthValue)
            if (response.code != 0 || response.data == null) {
                return Result.retry()
            }

            val overBudgets = response.data.items.filter { it.spent > it.amount }
            overBudgets.forEach { budget ->
                val spentYuan = "%.2f".format(budget.spent / 100.0)
                val amountYuan = "%.2f".format(budget.amount / 100.0)
                val name = budget.categoryName ?: "总预算"
                sendNotification(
                    title = "⚠️ ${name}已超支",
                    content = "已花费¥$spentYuan / 预算¥$amountYuan",
                    notificationId = budget.id
                )
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "BudgetCheckWorker failed")
            Result.retry()
        }
    }

    private fun sendNotification(title: String, content: String, notificationId: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId + NOTIFICATION_OFFSET, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当月预算超支时提醒"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val WORK_NAME = "budget_check_work"
        private const val CHANNEL_ID = "aibill_budget"
        private const val CHANNEL_NAME = "预算提醒"
        private const val NOTIFICATION_OFFSET = 50000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BudgetCheckWorker>(
                6, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
