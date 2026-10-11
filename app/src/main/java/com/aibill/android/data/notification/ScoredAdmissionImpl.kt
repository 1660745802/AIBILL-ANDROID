package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.notification.ScoredAdmission
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ScoredAdmission] 的实现：10s 评分窗口 + 60s 跨渠道判重。
 *
 * 状态（窗口池、定时任务、已认领记录）是跨通知的进程级状态，因此本类是 `@Singleton`。
 *
 * ## 两段式：判重判定在锁内，写库在锁外
 *
 * [admitMutex] 只保护**判定段**——跨渠道去重查询 + 认领必须原子，否则同一笔交易的
 * 跨渠道视图会被放行两次（两笔重复账）。
 *
 * 写库在调用方的回调里（含一次网络往返，最长 15s），不占这把锁：通知入账是每天几十次的
 * 核心循环，一条慢请求不该让整条流水线停摆。
 *
 * ## 为什么认领（markProcessed）也放进判定段
 *
 * 标记就是「这一笔已被认领」。放在网络请求**之后**会出现这样的窗口：第一笔的 POST 还在飞、
 * DB 里还没有 confirmed 行，此时同一笔的另一个渠道视图进来，内存与 DB 都查不到它 →
 * 两笔重复账。放进判定段，跨渠道视图在这段窗口内依然被拦下。
 *
 * ⚠️ 同渠道同包名同金额**不是**重复（两杯咖啡）：判定段会同时放行两条，它们各有自己的
 * `clientId`，各自 POST 互不影响。
 */
@Singleton
class ScoredAdmissionImpl @Inject constructor(
    private val notificationRecordDao: NotificationRecordDao,
    private val rulesManager: NotificationRulesManager,
    private val appLogger: AppLogger,
) : ScoredAdmission {

    /** 可在测试中替换的调度器 */
    @Volatile
    var testDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

    private val dedupWindowMs: Long
        get() = rulesManager.getRules().processor.dedupWindowSeconds * 1000L

    private val scoreWindowMs: Long
        get() = rulesManager.getRules().processor.scoringWindowSeconds * 1000L

    /** 判定段串行化：去重查询 + 认领必须原子 */
    private val admitMutex = Mutex()

    /** 一个评分窗口：当前最优候选 + 它的产出回调。 */
    private data class Window(
        val candidate: ParsedTransaction,
        val onAdmitted: suspend (ParsedTransaction) -> Unit,
    )

    private val windows = ConcurrentHashMap<Int, Window>() // key=amount
    private val windowJobs = ConcurrentHashMap<Int, Job>()

    // 不走 @ApplicationScope 注入：保留 testDispatcher 入口供单测替换。
    // 这里自建 SupervisorJob 且不提供 cancel()——本类仅由单例持有，进程退出时随 JVM 释放。
    private val scope by lazy {
        CoroutineScope(SupervisorJob() + (testDispatcher ?: kotlinx.coroutines.Dispatchers.IO))
    }

    /** 内存级已认领记录（用于跨渠道去重，比 DB 查询更快更准） */
    private data class ProcessedEntry(
        val amount: Int,
        val channel: SourceChannel,
        val packageName: String,
        val time: Long,
    )
    private val recentProcessed = ConcurrentLinkedDeque<ProcessedEntry>()

    override fun submit(candidate: ParsedTransaction, onAdmitted: suspend (ParsedTransaction) -> Unit) {
        val key = candidate.amount
        val existing = windows[key]

        if (existing != null && (candidate.capture.receivedAt - existing.candidate.capture.receivedAt) <= scoreWindowMs) {
            val sameSource = existing.candidate.capture.channel == candidate.capture.channel &&
                existing.candidate.capture.packageName == candidate.capture.packageName
            if (sameSource) {
                // 同渠道同包名同金额 = 两笔真实交易（如 10s 内两杯 ¥15 咖啡）。
                // 评分窗口的本意是合并「同一笔交易的跨渠道多视图」，不能把
                // 同来源的两笔合并丢一笔：立即提交前一笔，为新交易开新窗口。
                // （同一交易的重复通知更早会被预筛的内存 hash 去重 / DB 1s 去重拦掉）
                appLogger.debug(
                    "NLS",
                    "同来源同金额，视为两笔交易: amount=$key pkg=${candidate.capture.packageName}"
                )
                windowJobs[key]?.cancel()
                windowJobs.remove(key)
                windows.remove(key)
                scope.launch {
                    try {
                        admitNow(existing)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // flush 路径异常兜底：未捕获异常会崩掉进程，必须与窗口路径同级兜底
                        appLogger.error("NLS", "同源立即提交异常: amount=$key err=${e.message}")
                        Timber.e(e, "ScoredAdmission same-source flush failed: amount=$key")
                    }
                }
                openWindow(Window(candidate, onAdmitted))
                return
            }
            // 跨渠道同金额：同一笔交易的两个视图，比较 score 保留更好的。
            // 注意定时任务不重启：窗口锚点是先到那条的到达时刻。
            if (candidate.score > existing.candidate.score) {
                windows[key] = Window(candidate, onAdmitted)
                appLogger.debug(
                    "NLS",
                    "评分替换: amount=$key newScore=${candidate.score} > oldScore=${existing.candidate.score}"
                )
            } else {
                appLogger.debug(
                    "NLS",
                    "评分丢弃: amount=$key score=${candidate.score} ≤ existing=${existing.candidate.score}"
                )
            }
        } else {
            // 首条或超过评分窗口的新交易
            openWindow(Window(candidate, onAdmitted))
        }
    }

    /** 开一个新的评分窗口：N 秒后取池中候选产出。 */
    private fun openWindow(window: Window) {
        val key = window.candidate.amount
        windows[key] = window
        windowJobs[key]?.cancel()
        appLogger.debug(
            "NLS",
            "评分窗口启动: amount=$key score=${window.candidate.score} " +
                "isComplete=${window.candidate.isComplete} channel=${window.candidate.capture.channel}"
        )
        windowJobs[key] = scope.launch {
            try {
                delay(scoreWindowMs)
                val best = windows.remove(key) ?: run {
                    appLogger.warn("NLS", "评分窗口到期但池中无数据: amount=$key")
                    return@launch
                }
                admitNow(best)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 产出异常路径兜底：显式清理，确保任何 future 修改（如先 get/set 再产出）
                // 也不会留下脏数据。
                appLogger.error("NLS", "评分提交异常: amount=$key err=${e.message}")
                Timber.e(e, "ScoredAdmission scoring commit failed: amount=$key")
                windows.remove(key)
            } finally {
                windowJobs.remove(key)
            }
        }
    }

    /**
     * 判定段（锁内：去重 + 认领，必须原子）→ 通过则交回调用方产出（锁外，可含网络）。
     */
    private suspend fun admitNow(window: Window) {
        val candidate = window.candidate
        appLogger.debug(
            "NLS",
            "窗口产出: amount=${candidate.amount} score=${candidate.score} isComplete=${candidate.isComplete}"
        )

        val admitted = admitMutex.withLock {
            // 跨渠道去重（内存）
            if (isDuplicateAcrossChannels(
                    candidate.amount,
                    candidate.capture.channel,
                    candidate.capture.packageName,
                    candidate.capture.receivedAt,
                )
            ) {
                appLogger.debug("NLS", "去重(内存): channel=${candidate.capture.channel} amount=${candidate.amount}")
                return@withLock false
            }
            // 跨渠道去重（DB 兜底）
            val dbDuplicate = notificationRecordDao.findRecentConfirmedFromOtherChannel(
                candidate.amount,
                candidate.capture.receivedAt - dedupWindowMs,
                candidate.capture.packageName,
            )
            if (dbDuplicate != null) {
                appLogger.debug("NLS", "去重(DB): amount=${candidate.amount}")
                return@withLock false
            }
            markProcessed(candidate.amount, candidate.capture.channel, candidate.capture.packageName)
            true
        }
        if (!admitted) return

        window.onAdmitted(candidate)
    }

    /**
     * 跨渠道去重判断（内存级）：
     * - 不同 channel 或不同包名 + 同金额 + 60s 内 → 重复
     * - 同 channel 同包名 → 不去重（视为真实两笔交易）
     */
    private fun isDuplicateAcrossChannels(
        amount: Int,
        channel: SourceChannel,
        packageName: String,
        now: Long,
    ): Boolean {
        // 先清过期
        val cutoff = now - dedupWindowMs
        recentProcessed.removeIf { it.time < cutoff }
        // 查重：不同渠道 或 不同包名（同金额N秒内）
        return recentProcessed.any {
            it.amount == amount &&
                (it.channel != channel || it.packageName != packageName) &&
                (now - it.time) <= dedupWindowMs
        }
    }

    private fun markProcessed(amount: Int, channel: SourceChannel, packageName: String) {
        recentProcessed.addLast(ProcessedEntry(amount, channel, packageName, System.currentTimeMillis()))
    }
}
