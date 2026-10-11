package com.aibill.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.util.AppLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import retrofit2.HttpException
import java.io.IOException

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingTransactionDao: PendingTransactionDao,
    private val transactionApi: TransactionApi,
    private val syncLock: SyncLock,
    private val appLogger: AppLogger,
    private val tokenManager: TokenManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // 未登录时**直接短路**：不发任何请求，也不碰 pending 队列。
        // NetworkMonitor 会在 App 启动时排同步（这是预期的），若此时无 token 还照发，
        // 每条 pending 都会拿到 401 被标成 failed —— 白白污染队列，
        // 而它们本来可以在用户重新登录后继续上传。
        if (!tokenManager.hasToken()) {
            appLogger.info("SYNC", "无登录 token，跳过同步（pending 队列原样保留）")
            return Result.success()
        }
        // PR C1：置 sync lock，AuthRepositoryImpl 换号前会 wait 直到 release
        syncLock.acquire()
        try {
            val pendingList = pendingTransactionDao.getAllPending()
            if (pendingList.isEmpty()) return Result.success()

            appLogger.info("SYNC", "同步开始: ${pendingList.size}条待同步")
            var unauthorizedSeen = false

            for ((index, entity) in pendingList.withIndex()) {
                if (entity.retryCount >= MAX_RETRY_COUNT) {
                    appLogger.warn("SYNC", "超过最大重试: clientId=${entity.clientId}")
                    markFailed(entity.clientId, ERROR_MAX_RETRY_EXCEEDED)
                    continue
                }

                // R-Z：快照（getAllPending）与 POST 之间行可能已被用户撤销删除。
                // 此时先补检查一次——不 POST、不 markFailed、不 markSynced、不计失败。
                // 与 R-Y 补偿互补：R-Y 关的是「POST 后、回写前」（服务端已建，
                // 用 markSynced 返回 0 行发现）；R-Z 关的是「回写前、POST 前」，
                // 直接把幽灵账扼杀在发送之前。
                // 刻意不看 live.syncStatus：此刻只要行还在，该发就发。
                val live = pendingTransactionDao.findByClientId(entity.clientId)
                if (live == null) {
                    appLogger.warn("SYNC", "行已被撤销删除，跳过不 POST: clientId=${entity.clientId}")
                    continue
                }

                val (result, serverId) = syncTransaction(entity)
                when (result) {
                    SyncResult.SUCCESS -> {
                        val persisted = markSynced(entity.clientId, serverId)
                        if (serverId != null && !persisted) {
                            // TOCTOU 补偿：doWork 开头就把 pending 队列读进了内存，
                            // 期间用户可能点了通知里的「撤销」——pending 分支只删本地。
                            // 于是服务端这笔已经建好、本地却没人要了（幽灵账）。
                            // markSynced 影响 0 行就是这条 pending 已被删的证据，
                            // 拿它手里的 serverId 反向删服务端，让两边回到一致。
                            compensateUserUndo(entity.clientId, serverId)
                        } else {
                            appLogger.info("SYNC", "同步成功: clientId=${entity.clientId} serverId=$serverId")
                        }
                    }
                    SyncResult.UNAUTHORIZED -> {
                        // Token 过期：标记当前记录失败并中断本轮遍历。
                        // AuthEventBus 已被 AuthInterceptor emit，由 MainActivity 触发跳登录。
                        // reason 必须经 markFailed 原子写入 last_error——它与
                        // ERROR_TOKEN_EXPIRED_PATTERN 配对，用户同账号重新登录后
                        // AuthRepositoryImpl 按该模式把这些记录重置回 pending 续传。
                        markFailed(entity.clientId, ERROR_TOKEN_EXPIRED)
                        unauthorizedSeen = true
                        appLogger.error("SYNC", "401 Token过期: clientId=${entity.clientId}")

                        // 立即中断遍历：AuthInterceptor 在第一个 401 就清了 session，
                        // 继续逐条发只会把剩余 N-1 条 pending 也污染成 failed，
                        // 并对同一个原因重复发 N 次请求。它们保持 'pending'，
                        // 等用户重新登录后由 onSameAccountRelogin 排的 SyncWorker 续传。
                        val remaining = pendingList.size - index - 1
                        if (remaining > 0) {
                            appLogger.warn("SYNC", "401 中断遍历，剩余 $remaining 条 pending 保持待同步")
                        }
                        break
                    }
                    // PR H2：服务端 code==0 但 created[]/duplicates[] 都没有匹配
                    // 的 clientId（服务端漏字段/转换异常），不标 SUCCESS 否则
                    // 后续无 serverId 无法编辑/删除（僵尸记录）。
                    SyncResult.SERVER_ANOMALY -> {
                        markFailed(entity.clientId, ERROR_SERVER_ANOMALY)
                        appLogger.error("SYNC", "服务端异常(无clientId匹配): clientId=${entity.clientId}")
                    }
                    SyncResult.BUSINESS_ERROR -> {
                        markFailed(entity.clientId, ERROR_BUSINESS)
                        appLogger.warn("SYNC", "业务错误: clientId=${entity.clientId}")
                    }
                    SyncResult.NETWORK_ERROR -> {
                        incrementRetry(entity.clientId, ERROR_NETWORK)
                        appLogger.warn("SYNC", "网络错误,将重试: clientId=${entity.clientId} retry=${entity.retryCount + 1}")
                    }
                }
            }

            // 检查剩余 pending（含 failed 状态）数，如果还有任何未同步的，则 retry
            val remainingCount = pendingTransactionDao.getAnyUnsyncedCount()
            return when {
                unauthorizedSeen -> {
                    appLogger.error("SYNC", "同步结束: 遇到401, 等待重新登录")
                    Result.failure()   // 等用户重新登录后再调度
                }
                remainingCount > 0 -> {
                    appLogger.info("SYNC", "同步结束: 还有${remainingCount}条未同步, 将retry")
                    Result.retry()
                }
                else -> {
                    appLogger.info("SYNC", "同步结束: 全部成功")
                    Result.success()
                }
            }
        } finally {
            // PR C1：无论成功/异常/重试，必须释放锁，否则后续 login 会死等
            syncLock.release()
        }
    }

    /**
     * 同步单条 pending 交易，返回 (结果, 服务端 id)。
     * 服务端 id 来自 create 接口的 created[] 列表（按 client_id 匹配）。
     */
    internal suspend fun syncTransaction(entity: PendingTransactionEntity): Pair<SyncResult, Int?> {
        return try {
            val request = CreateTransactionRequest(
                items = listOf(entity.toItemRequest())
            )
            val apiResponse = transactionApi.createTransactions(request)
            val data = apiResponse.data
            // 在 created 或 duplicates 中按 client_id 查找对应记录
            val matched = data?.created?.firstOrNull { it.clientId == entity.clientId }
                ?: data?.duplicates?.firstOrNull { it.clientId == entity.clientId }
            // PR H2：服务端返回 code==0 但两条列表都找不到匹配的 clientId，
            // 视为服务端异常（漏字段/转换错误），返回 SERVER_ANOMALY 让 Worker
            // 走 markFailed 路径，避免本地标 synced 但没 serverId 的僵尸记录
            when {
                data == null -> SyncResult.SERVER_ANOMALY to null
                matched == null -> SyncResult.SERVER_ANOMALY to null
                else -> SyncResult.SUCCESS to matched.id
            }
        } catch (e: HttpException) {
            when (e.code()) {
                HTTP_UNAUTHORIZED -> SyncResult.UNAUTHORIZED to null
                else -> SyncResult.BUSINESS_ERROR to null
            }
        } catch (_: IOException) {
            SyncResult.NETWORK_ERROR to null
        }
    }

    /**
     * 标记同步成功并写入服务端返回的 transaction id，便于后续编辑/删除追溯。
     *
     * @return true = 本地这行被成功回写；false = **影响 0 行**，即这条 pending 已被删除。
     */
    private suspend fun markSynced(clientId: String, serverId: Int?): Boolean {
        return if (serverId != null) {
            pendingTransactionDao.markSynced(
                clientId = clientId,
                serverId = serverId,
                status = STATUS_SYNCED,
                updatedAt = System.currentTimeMillis()
            ) > 0
        } else {
            pendingTransactionDao.updateSyncStatus(
                clientId = clientId,
                status = STATUS_SYNCED,
                updatedAt = System.currentTimeMillis()
            )
            // 没有 serverId 就无从补偿（也正因为如此，正常路径不该走到这里），
            // 降级行为与历史实现保持一致。
            true
        }
    }

    /**
     * 撤销补偿：用户已删掉本地 pending，但服务端这笔已经建好，删掉它让两边一致。
     *
     * 复用 worker 已持有的 [transactionApi]，**不**注入 TransactionRepository——
     * worker 的依赖图越小越好，且这里只需原始 code 判定，不需要 Result 封装。
     *
     * 失败处理原则（宁可留证据，不可静默丢失）：
     * - 补成功 → 顺手 [PendingTransactionDao.deleteByClientId] 清理可能残留的本地行。
     * - 补失败/抛异常 → **绝不写成功态、绝不删本地行**，
     *   只把 clientId + serverId 打进 error 日志作为事后排查证据。
     *
     * 整个方法包在 try/catch 里：补偿失败绝不能让 doWork 崩溃或影响后续 pending。
     */
    private suspend fun compensateUserUndo(clientId: String, serverId: Int) {
        try {
            val response = transactionApi.deleteTransaction(serverId)
            if (response.code == 0) {
                pendingTransactionDao.deleteByClientId(clientId)
                appLogger.info(
                    "SYNC",
                    "撤销补偿: 本地已被用户撤销，已同步删除服务端 " +
                        "clientId=$clientId serverId=$serverId",
                )
            } else {
                appLogger.error(
                    "SYNC",
                    "撤销补偿失败: 服务端 code=${response.code} message=${response.message} " +
                        "clientId=$clientId serverId=$serverId",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 补偿是挂起调用：worker 被取消（换号 awaitSyncIdle / 系统回收）时
            // 必须让取消正常传播，不能当成「补偿异常」吞掉，否则破坏结构化并发。
            throw e
        } catch (e: Exception) {
            appLogger.error(
                "SYNC",
                "撤销补偿异常: clientId=$clientId serverId=$serverId error=${e.message}",
            )
        }
    }

    /**
     * 标记为终态失败。
     *
     * 必须走 [PendingTransactionDao.markFailed]（同一条 UPDATE 写
     * sync_status + last_error + updated_at），**不可**改用
     * [PendingTransactionDao.updateSyncStatus]——后者不写 last_error，
     * 会让 [PendingTransactionDao.resetFailedMatching] 恒匹配不到，
     * 使 401 这类可恢复失败变成真终态、离线记账永久卡死。
     */
    private suspend fun markFailed(clientId: String, error: String) {
        Timber.w("SyncWorker: 标记为失败 clientId=%s reason=%s", clientId, error)
        pendingTransactionDao.markFailed(
            clientId = clientId,
            error = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun incrementRetry(clientId: String, error: String) {
        pendingTransactionDao.incrementRetryCount(
            clientId = clientId,
            error = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun PendingTransactionEntity.toItemRequest(): TransactionItemRequest {
        return TransactionItemRequest(
            clientId = clientId,
            clientType = "app_android",
            source = source,
            type = type,
            amount = amount,
            categoryId = categoryId,
            accountId = accountId,
            description = description,
            date = date,
            time = time,
            tags = tags?.split(",")?.filter { it.isNotBlank() },
            clientCreatedAt = clientCreatedAt
        )
    }

    internal enum class SyncResult {
        SUCCESS,
        UNAUTHORIZED,
        /** PR H2：服务端 code==0 但返回数据里没有匹配的 clientId */
        SERVER_ANOMALY,
        BUSINESS_ERROR,
        NETWORK_ERROR,
    }

    companion object {
        const val WORK_NAME = "transaction_sync"
        private const val MAX_RETRY_COUNT = 5
        private const val HTTP_UNAUTHORIZED = 401
        private const val STATUS_SYNCED = "synced"

        /**
         * 401 失败原因（原子写入 `last_error`）。
         * 与 [ERROR_TOKEN_EXPIRED_PATTERN] 配对使用，改一处必须改另一处。
         */
        const val ERROR_TOKEN_EXPIRED = "Token expired, need re-login"

        /**
         * 供 DAO 按 last_error LIKE 匹配时使用的模式。
         * AuthRepositoryImpl 登录成功（同账号）后用它把 401 记录重置为 pending。
         */
        const val ERROR_TOKEN_EXPIRED_PATTERN = "%Token expired%"

        /**
         * 以下三个是**不可恢复**失败原因：不能被 [ERROR_TOKEN_EXPIRED_PATTERN] 命中，
         * 否则会被重登时无限重置成死循环。提取为常量是为了让
         * `SyncWorkerSyncTransactionTest` 能断言「reason 被精确传入 DAO」，
         * 而不是断言一个重复两遍的字符串字面量。
         */
        const val ERROR_MAX_RETRY_EXCEEDED = "Max retry count exceeded"
        const val ERROR_SERVER_ANOMALY =
            "Server response missing clientId match (created/duplicates both empty)"
        const val ERROR_BUSINESS = "Business error"

        /** 唯一可重试原因（走 incrementRetryCount，不改 sync_status） */
        const val ERROR_NETWORK = "Network error"
    }
}
