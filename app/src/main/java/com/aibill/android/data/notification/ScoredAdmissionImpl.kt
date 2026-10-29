package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.notification.ScoredAdmission
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/** 10s 评分择优、60s 同额判重；真实短时同额交易也合并的产品口径不变。 */
@Singleton
class ScoredAdmissionImpl @Inject constructor(
    private val notificationRecordDao: NotificationRecordDao,
    private val ruleSource: NotificationRuleSource,
    private val appLogger: AppLogger,
) : ScoredAdmission {

    @Volatile
    var testDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

    private val dedupWindowMs: Long
        get() = ruleSource.snapshot().dedupWindowSeconds * 1000L

    private val scoreWindowMs: Long
        get() = ruleSource.snapshot().scoringWindowSeconds * 1000L

    private val admitMutex = Mutex()

    private data class Window(
        val id: Long,
        val generation: Long,
        val anchorAt: Long,
        val durationMs: Long,
        val candidate: ParsedTransaction,
        val onAdmitted: suspend (ParsedTransaction) -> Unit,
    )

    // 同额的历史窗口可并存，timer 只能摘取自己的 id。
    private val windows = linkedMapOf<Long, Window>()
    private var nextWindowId = 0L
    @Volatile private var generation = 0L

    override suspend fun reset() {
        admitMutex.withLock {
            synchronized(this) {
                generation += 1
                windows.clear()
                scope.coroutineContext.cancelChildren()
            }
            recentProcessed.clear()
        }
    }

    private val scope by lazy {
        CoroutineScope(SupervisorJob() + (testDispatcher ?: kotlinx.coroutines.Dispatchers.IO))
    }

    private data class ProcessedEntry(
        val amount: Int,
        val receivedAt: Long,
        val claimedAt: Long,
    )
    private val recentProcessed = mutableListOf<ProcessedEntry>()

    override fun submit(candidate: ParsedTransaction, onAdmitted: suspend (ParsedTransaction) -> Unit) {
        synchronized(this) {
            val existing = windows.values.firstOrNull {
                it.candidate.amount == candidate.amount &&
                    abs(candidate.capture.receivedAt - it.anchorAt) <= it.durationMs
            }
            if (existing != null) {
                if (candidate.score > existing.candidate.score) {
                    windows[existing.id] = existing.copy(candidate = candidate, onAdmitted = onAdmitted)
                    appLogger.debug("NLS", "评分替换: amount=${candidate.amount} score=${candidate.score}")
                }
                return
            }
            val window = Window(
                id = nextWindowId++,
                generation = generation,
                anchorAt = candidate.capture.receivedAt,
                durationMs = scoreWindowMs,
                candidate = candidate,
                onAdmitted = onAdmitted,
            )
            windows[window.id] = window
            openWindow(window)
        }
    }

    private fun openWindow(window: Window) {
        scope.launch {
            try {
                delay(window.durationMs)
                val best = synchronized(this@ScoredAdmissionImpl) {
                    windows.remove(window.id)
                } ?: return@launch
                admitNow(best)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.error("NLS", "评分提交异常: amount=${window.candidate.amount} err=${e.message}")
                Timber.e(e, "ScoredAdmission scoring commit failed")
            } finally {
                synchronized(this@ScoredAdmissionImpl) { windows.remove(window.id) }
            }
        }
    }

    private suspend fun admitNow(window: Window) {
        val candidate = window.candidate
        val admitted = admitMutex.withLock {
            if (window.generation != generation) return@withLock false
            val duration = dedupWindowMs
            val receivedAt = candidate.capture.receivedAt
            val now = System.currentTimeMillis()
            // 保留刚认领的历史重放；不能按 receivedAt 清掉尚在提交中的记录。
            recentProcessed.removeAll { it.claimedAt < now - duration }
            if (recentProcessed.any {
                    it.amount == candidate.amount && abs(receivedAt - it.receivedAt) <= duration
                }
            ) {
                appLogger.debug("NLS", "去重(内存): amount=${candidate.amount}")
                return@withLock false
            }
            val dbDuplicate = notificationRecordDao.findRecentSameAmount(
                amount = candidate.amount,
                since = receivedAt - duration,
                until = receivedAt + duration,
            )
            if (dbDuplicate != null) {
                appLogger.debug("NLS", "去重(DB): amount=${candidate.amount}")
                return@withLock false
            }
            recentProcessed += ProcessedEntry(candidate.amount, receivedAt, now)
            true
        }
        // 认领在锁内，网络和落库在锁外。
        if (admitted) window.onAdmitted(candidate)
    }
}
