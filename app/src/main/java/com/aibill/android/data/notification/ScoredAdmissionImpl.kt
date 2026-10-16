package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.ParsedTransaction
import com.aibill.android.domain.notification.ScoredAdmission
import com.aibill.android.domain.notification.NotificationRuleSource
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
import kotlin.math.abs
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
    private val ruleSource: NotificationRuleSource,
    private val appLogger: AppLogger,
) : ScoredAdmission {

    /** 可在测试中替换的调度器 */
    @Volatile
    var testDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null

    private val dedupWindowMs: Long
        get() = ruleSource.snapshot().dedupWindowSeconds * 1000L

    private val scoreWindowMs: Long
        get() = ruleSource.snapshot().scoringWindowSeconds * 1000L

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

    /** 内存级已认领记录（用于同额去重，比 DB 查询更快；DB 层在进程重启后仍能兜底）。 */
    private data class ProcessedEntry(
        val amount: Int,
        val time: Long,
    )
    private val recentProcessed = ConcurrentLinkedDeque<ProcessedEntry>()

    override fun submit(candidate: ParsedTransaction, onAdmitted: suspend (ParsedTransaction) -> Unit) {
        // 判定段（读窗口 → 同额择优 / 开新窗口）必须原子：
        // submit 从 NLS/SMS/A11Y 三条协程并发进来，并发的同金额两条会各自看到
        // 「池子为空」而互相覆盖窗口，先到那笔被静默丢掉。
        synchronized(this) {
            val key = candidate.amount
            val existing = windows[key]

            if (existing != null &&
                (candidate.capture.receivedAt - existing.candidate.capture.receivedAt) <= scoreWindowMs
            ) {
                // **短时同额 = 同一笔**，不分来源、不分包名。
                //
                // 【为什么改了口径】改动前这里是「同渠道同包名同金额 = 两笔真实交易」
                // （如 10s 内两杯 ¥15 咖啡），只有跨渠道才择优合并。它的前提写在原注释里：
                // 「同一交易的重复通知更早会被预筛的内存 hash 去重 / DB 1s 去重拦掉」。
                //
                // **但那个前提不成立**：内容去重键是 `包名 + fullText.hashCode()`
                // （NotificationPrefilterImpl.contentKeyOf），而 NLS 的 fullText 把
                // **标题拼在正文最前面**（NotificationMonitorService.buildFullText）。
                // 同一条银行通知被重发时标题一变（例：「招商银行」→「信用卡通知」），
                // fullText 就变了 → 5s 内存去重与 5min AI_MEMORY 双双失效 →
                // 于是这条本来用来「保护真交易」的规则，反过来把**同一条通知的两个视图**
                // 判成两笔、各自提交一份账单。
                //
                // 线上实证（2026-10-16）：¥1.50 一笔真实消费弹出 2 份待审、
                // ¥65.06 产生 2 条账（客户端日志：同来源同金额，视为两笔交易: amount=150 pkg=cmb.pb）。
                //
                // 【代价，明确接受】短时间内**真实**发生的两笔同额交易会被合并成一笔
                // （两杯同价咖啡）。这是产品口径的选择而非疏漏：宁可少记一笔，
                // 也不要同一笔弹出很多份——后者会让用户直接不信这个 App。
                //
                // 择优规则不变：分数高的留下；注意定时任务不重启，
                // 窗口锚点是先到那条的到达时刻。
                if (candidate.score > existing.candidate.score) {
                    windows[key] = Window(candidate, onAdmitted)
                    appLogger.debug(
                        "NLS",
                        "评分替换: amount=$key newScore=${candidate.score} > oldScore=${existing.candidate.score}"
                    )
                } else {
                    appLogger.debug(
                        "NLS",
                        "短时同额合并为一笔: amount=$key score=${candidate.score} ≤ " +
                            "existing=${existing.candidate.score} pkg=${candidate.capture.packageName}"
                    )
                }
            } else {
                // 首条或超过评分窗口的新交易
                openWindow(Window(candidate, onAdmitted))
            }
        }
    }

    /** 开一个新的评分窗口：N 秒后取池中候选产出。 */
    private fun openWindow(window: Window) {
        val key = window.candidate.amount
        windows[key] = window
        windowJobs.remove(key)?.cancel()
        appLogger.debug(
            "NLS",
            "评分窗口启动: amount=$key score=${window.candidate.score} " +
                "isComplete=${window.candidate.isComplete} channel=${window.candidate.capture.channel}"
        )
        windowJobs[key] = scope.launch {
            // 产出前先按身份摘掉自己的映射：这一步之后本任务不再接受 cancel。
            // 与同源 flush 的配合（两条路径竞争同一窗口）恰好产出一次：
            // - 本任务先摘映射 → flush 的 windows.remove 拿到 null，不再 flush，由本任务产出；
            // - flush 先取走窗口 → 本任务 windows.remove 拿到 null，直接退出。
            try {
                delay(scoreWindowMs)
                windowJobs.remove(key, coroutineContext[Job])
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
                // 只摘自己的映射：若已被同源新窗口覆盖，不能把新窗口的 job 映射误删
                // （误删会让新窗口的计时任务之后无法被 cancel）。
                windowJobs.remove(key, coroutineContext[Job])
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
            // 同额去重（内存，60s）
            if (isRecentSameAmount(candidate.amount, candidate.capture.receivedAt)) {
                appLogger.debug("NLS", "去重(内存): amount=${candidate.amount} pkg=${candidate.capture.packageName}")
                return@withLock false
            }
            // 同额去重（DB 兜底：进程被杀后内存层失效，这一层兼跨进程）
            val dbDuplicate = notificationRecordDao.findRecentSameAmount(
                candidate.amount,
                candidate.capture.receivedAt - dedupWindowMs,
            )
            if (dbDuplicate != null) {
                appLogger.debug("NLS", "去重(DB): amount=${candidate.amount}")
                return@withLock false
            }
            markProcessed(candidate.amount, candidate.capture.receivedAt)
            true
        }
        if (!admitted) return

        window.onAdmitted(candidate)
    }

    /**
     * 同额去重判断（内存级）：**同金额 + [dedupWindowSeconds] 内 = 同一笔**，不分渠道、不分包名。
     *
     * ## 口径变更（与「短时同额当作一个账单」对齐）
     *
     * 改动前这里带一个**排除条件**（`不同渠道 或 不同包名`），即同包名的同额一律放过。
     * 它放过了什么——线上实证（2026-10-16 ¥65.06，同一家、同一笔消费的两个通知视图）：
     * `Alipay 08:19:28 支付成功 ￥65.06…完成` 与 `Alipay 08:20:00 支付成功 ￥65.06…付款方式`，
     * 相隔 33s，两条账都入了。
     *
     * ## 为什么改这一层，而不去拉长评分窗口（`scoring_window_seconds`）
     *
     * 评分窗口是「**合并后再产出**」——拉长它等于每一笔账都晚 50s 出现在待审里；
     * 这一层是「**先产出、后判重**」——60s 窗口本来就在（`dedup_window_seconds`），
     * 这里只是把原本漏掉的同包名情况纳进来，**零延迟代价**。
     *
     * ## 代价（与评分窗口那处一致，已明确接受）
     *
     * 60s 内**真实**发生的两笔同额会被合并成一笔。
     *
     * 用绝对时间差：重放候选的 receivedAt 可能早于已登记条目，单边差值为负
     * 会误判成「窗口内」而把历史交易当重复丢掉。
     */
    private fun isRecentSameAmount(amount: Int, now: Long): Boolean {
        // 过期清理用 wall clock：entries 与查询锚点都以 receivedAt 为时间轴，
        // 401 重放进来的是历史 receivedAt——若用它当清理基准会把尚在窗口内的
        // 新鲜条目整批删掉，之后的判重全部失效。
        val cutoff = System.currentTimeMillis() - dedupWindowMs
        recentProcessed.removeIf { it.time < cutoff }
        return recentProcessed.any { it.amount == amount && abs(now - it.time) <= dedupWindowMs }
    }

    private fun markProcessed(amount: Int, receivedAt: Long) {
        // 时间轴统一用 receivedAt（与查询侧同源）：wall clock 与捕获时刻混用
        // 会让重放（历史 receivedAt）的判定结果不可预测。
        recentProcessed.addLast(ProcessedEntry(amount = amount, time = receivedAt))
    }
}
