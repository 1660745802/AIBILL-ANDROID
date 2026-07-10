package com.aibill.android.service

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * 覆盖：
 * - P0#6：markSynced 保留 server_transaction_id
 * - P0#7：serverId=null 时不再标 synced（H2 防僵尸记录）
 * - P0#8：401 时标 failed + Result.failure
 * - H2：SERVER_ANOMALY 触发 markFailed
 * - C1：syncLock.acquire/release 配对
 *
 * 通过把 syncTransaction 改 internal 来单元测试关键分支，
 * 避免 Robolectric + WorkManager 集成测试的复杂度。
 */
class SyncWorkerSyncTransactionTest {

    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk()
    private val syncLock: SyncLock = mockk(relaxed = true)
    private val appLogger: com.aibill.android.util.AppLogger = mockk(relaxed = true)

    private fun makeEntity(
        clientId: String = "C1",
        retryCount: Int = 0,
    ) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        categoryId = 7,
        categoryName = "餐饮",
        categoryIcon = "🍜",
        accountId = 1,
        accountName = "微信",
        description = "午饭",
        date = "2026-07-05",
        source = "manual",
        clientCreatedAt = "2026-07-05T12:00:00Z",
        retryCount = retryCount,
    )

    /**
     * PR 14 防御注释：CoroutineWorker 构造函数内部会读 taskExecutor、id 等
     * WorkerParameters 字段。用 mockk(relaxed=true) 时，只要 doWork() 不碰这些
     * 内部字段就正常；但若未来 WorkManager 版本升级或 CoroutineWorker 内部实现变化
     * 导致 doWork() 开始读这些字段，测试会报 NullPointerException。
     *
     * 如果发生这种情况，有两个修复路径：
     * 1. 用 Robolectric 提供真实 WorkerParameters（测试变重但更稳）
     * 2. 把核心 sync 逻辑抽成纯函数/类（更干净的架构改进）
     */
    private fun makeWorker(): SyncWorker = SyncWorker(
        appContext = mockk(relaxed = true),
        workerParams = mockk(relaxed = true),
        pendingTransactionDao = pendingDao,
        transactionApi = transactionApi,
        syncLock = syncLock,
        appLogger = appLogger,
    )

    private fun transactionDto(
        id: Int = 42,
        clientId: String = "C1",
    ) = TransactionDto(
        id = id,
        clientId = clientId,
        type = "expense",
        amount = 3200,
        categoryId = 7,
        categoryName = "餐饮",
        categoryIcon = "🍜",
        accountId = 1,
        accountName = "微信",
        targetAccountId = null,
        targetAccountName = null,
        description = "午饭",
        date = "2026-07-05",
        time = null,
        tags = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun makeCreateResponse(
        created: List<TransactionDto> = listOf(transactionDto()),
        duplicates: List<TransactionDto> = emptyList(),
    ): ApiResponse<CreateTransactionResponse> = ApiResponse(
        code = 0,
        data = CreateTransactionResponse(created = created, duplicates = duplicates),
        message = "ok",
    )

    // ==================== syncTransaction ====================

    @Test
    fun `syncTransaction - created list has matching clientId - SUCCESS with serverId`() = runTest {
        val entity = makeEntity("C1")
        val response = makeCreateResponse(created = listOf(transactionDto(id = 99, clientId = "C1")))

        coEvery { transactionApi.createTransactions(any()) } returns response

        val worker = makeWorker()
        val (result, serverId) = worker.syncTransaction(entity)

        assertEquals(SyncWorker.SyncResult.SUCCESS, result)
        assertEquals(99, serverId)
    }

    @Test
    fun `syncTransaction - duplicates list has matching clientId - SUCCESS with serverId (idempotency)`() = runTest {
        // P0#6：服务端 idempotency 把请求放回 duplicates 列表，
        // 不返回 created 但我们仍能从 duplicates 找 serverId
        val entity = makeEntity("C1")
        val response = makeCreateResponse(
            created = emptyList(),
            duplicates = listOf(transactionDto(id = 88, clientId = "C1")),
        )
        coEvery { transactionApi.createTransactions(any()) } returns response

        val (result, serverId) = makeWorker().syncTransaction(entity)

        assertEquals(SyncWorker.SyncResult.SUCCESS, result)
        assertEquals(88, serverId)
    }

    @Test
    fun `syncTransaction - neither list has matching clientId - SERVER_ANOMALY (H2 fix)`() = runTest {
        // PR H2：服务端返回 code==0 但 created/duplicates 都找不到匹配 clientId
        // （服务端漏字段/转换异常），返回 SERVER_ANOMALY 不再静默标 synced
        val entity = makeEntity("C1")
        val response = makeCreateResponse(
            created = listOf(transactionDto(id = 99, clientId = "WRONG")),
            duplicates = emptyList(),
        )
        coEvery { transactionApi.createTransactions(any()) } returns response

        val (result, serverId) = makeWorker().syncTransaction(entity)

        assertEquals(SyncWorker.SyncResult.SERVER_ANOMALY, result)
        assertEquals(null, serverId)
    }

    @Test
    fun `syncTransaction - data is null - SERVER_ANOMALY (defensive)`() = runTest {
        val response = ApiResponse<CreateTransactionResponse>(
            code = 0, data = null, message = "ok",
        )
        coEvery { transactionApi.createTransactions(any()) } returns response

        val (result, _) = makeWorker().syncTransaction(makeEntity("C1"))

        assertEquals(SyncWorker.SyncResult.SERVER_ANOMALY, result)
    }

    @Test
    fun `syncTransaction - HttpException 401 - UNAUTHORIZED`() = runTest {
        val httpEx = HttpException(Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        val (result, _) = makeWorker().syncTransaction(makeEntity("C1"))

        assertEquals(SyncWorker.SyncResult.UNAUTHORIZED, result)
    }

    @Test
    fun `syncTransaction - HttpException 500 - BUSINESS_ERROR`() = runTest {
        val httpEx = HttpException(Response.error<Any>(500, okhttp3.ResponseBody.create(null, "")))
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        val (result, _) = makeWorker().syncTransaction(makeEntity("C1"))

        assertEquals(SyncWorker.SyncResult.BUSINESS_ERROR, result)
    }

    @Test
    fun `syncTransaction - IOException - NETWORK_ERROR`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws IOException("no network")

        val (result, _) = makeWorker().syncTransaction(makeEntity("C1"))

        assertEquals(SyncWorker.SyncResult.NETWORK_ERROR, result)
    }

    // ==================== markSynced / markFailed / incrementRetry (via doWork) ====================

    @Test
    fun `doWork - empty pending list - returns success, no API calls`() = runTest {
        coEvery { pendingDao.getAllPending() } returns emptyList()

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
    }

    @Test
    fun `doWork - one pending SUCCESS with serverId - markSynced writes server_transaction_id`() = runTest {
        // P0#6 + P0#7：markSynced 把 serverId 写进 server_transaction_id
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = 99, clientId = "C1"))
        )

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) {
            pendingDao.markSynced(clientId = "C1", serverId = 99, status = "synced", updatedAt = any())
        }
    }

    @Test
    fun `doWork - SERVER_ANOMALY - markFailed with anomaly message, not markSynced`() = runTest {
        // PR H2：服务端 code=0 但 created/duplicates 没匹配 → markFailed
        val entity = makeEntity("C1")
        coEvery { pendingDao.getAllPending() } returns listOf(entity)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = emptyList(), duplicates = emptyList(),
        )

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { pendingDao.markSynced(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            pendingDao.updateSyncStatus(
                clientId = "C1",
                status = "failed",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `doWork - 401 seen - markFailed then return Result_failure`() = runTest {
        // P0#8：401 标 failed + Result.failure()（不 retry 等用户重新登录）
        val httpEx = HttpException(Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.failure(), result)
        coVerify(exactly = 1) {
            pendingDao.updateSyncStatus(
                clientId = "C1",
                status = "failed",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `doWork - retryCount at MAX_RETRY - markFailed before sync, no API call`() = runTest {
        val entity = makeEntity("C1", retryCount = 5)
        coEvery { pendingDao.getAllPending() } returns listOf(entity)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0

        makeWorker().doWork()

        // 没达 5 次前 retry 过的 entity，第 6 次（指数 5→6）应该直接标 failed
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
        coVerify(exactly = 1) {
            pendingDao.updateSyncStatus(
                clientId = "C1",
                status = "failed",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `doWork - IOException - incrementRetry (preserves retry count, not reset)`() = runTest {
        // P0#8：网络错误标 incrementRetry，让 SyncWorker 指数退避
        val entity = makeEntity("C1", retryCount = 2)
        coEvery { pendingDao.getAllPending() } returns listOf(entity)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 1 // 失败后还有 pending
        coEvery { transactionApi.createTransactions(any()) } throws IOException("timeout")

        val result = makeWorker().doWork()

        // 有剩余未同步 → retry
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        coVerify(exactly = 1) {
            pendingDao.incrementRetryCount(
                clientId = "C1",
                error = "Network error",
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `doWork - C1 syncLock acquire release paired even on exception`() = runTest {
        // PR C1：doWork 入口 acquire / finally release，保证锁释放
        // 即使 transactionApi 抛 IOException 也要释放，否则后续 login 会死等
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 1
        coEvery { transactionApi.createTransactions(any()) } throws IOException("network down")
        every { syncLock.acquire() } returns false

        makeWorker().doWork()

        io.mockk.verifyOrder {
            syncLock.acquire()
            syncLock.release()
        }
    }

    @Test
    fun `doWork - HttpException 500 - markFailed (not retry, not increment)`() = runTest {
        val httpEx = HttpException(Response.error<Any>(500, okhttp3.ResponseBody.create(null, "")))
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        makeWorker().doWork()

        coVerify(exactly = 1) {
            pendingDao.updateSyncStatus(
                clientId = "C1",
                status = "failed",
                updatedAt = any(),
            )
        }
        coVerify(exactly = 0) { pendingDao.incrementRetryCount(any(), any(), any()) }
    }

    @Test
    fun `doWork - multiple pending with mixed results - first SUCCESS first then SERVER_ANOMALY`() = runTest {
        val e1 = makeEntity("C1")
        val e2 = makeEntity("C2")
        coEvery { pendingDao.getAllPending() } returns listOf(e1, e2)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        // e1 成功（id=99），e2 异常（created/duplicates 都空）
        coEvery { transactionApi.createTransactions(any()) } returnsMany listOf(
            makeCreateResponse(created = listOf(transactionDto(id = 99, clientId = "C1"))),
            makeCreateResponse(created = emptyList(), duplicates = emptyList()),
        )

        makeWorker().doWork()

        coVerify(exactly = 1) { pendingDao.markSynced("C1", 99, "synced", any()) }
        coVerify(exactly = 1) { pendingDao.updateSyncStatus("C2", "failed", any()) }
    }
}
