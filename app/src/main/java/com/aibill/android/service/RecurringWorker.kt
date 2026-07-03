package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.dao.RecurringDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recurringDao: RecurringDao,
    private val pendingTransactionDao: PendingTransactionDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val today = LocalDate.now()
            val dayOfMonth = today.dayOfMonth
            val rules = recurringDao.getRulesForDay(dayOfMonth)

            rules.forEach { rule ->
                if (!isExecutedThisMonth(rule.lastExecutedAt, today)) {
                    val now = LocalTime.now()
                    val transaction = PendingTransactionEntity(
                        clientId = UUID.randomUUID().toString(),
                        type = rule.type,
                        amount = rule.amount,
                        categoryId = rule.categoryId,
                        accountId = rule.accountId,
                        description = rule.description ?: rule.name,
                        date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        time = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                        source = "recurring",
                        clientCreatedAt = Instant.now().toString(),
                        syncStatus = "pending",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    pendingTransactionDao.insert(transaction)
                    recurringDao.updateLastExecuted(rule.id, System.currentTimeMillis())
                    Timber.d("Recurring executed: ${rule.name}")
                }
            }

            // 触发同步
            SyncScheduler.scheduleSyncIfNeeded(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "RecurringWorker failed")
            Result.retry()
        }
    }

    private fun isExecutedThisMonth(lastExecutedAt: Long?, today: LocalDate): Boolean {
        if (lastExecutedAt == null) return false
        val lastDate = Instant.ofEpochMilli(lastExecutedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return lastDate.year == today.year && lastDate.monthValue == today.monthValue
    }

    companion object {
        const val WORK_NAME = "recurring_work"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecurringWorker>(
                1, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
