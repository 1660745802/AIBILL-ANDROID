package com.aibill.android.service

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
 * ## Phase 0 新增不变量（否则 401 恢复机制是死代码）
 *
 * 历史 bug：[markFailed] 误调 [PendingTransactionDao.updateSyncStatus]，该 UPDATE
 * 不写 `last_error`，而 [PendingTransactionDao.resetFailedMatching] 的 WHERE 依赖
 * `last_error LIKE '%Token expired%'` —— 于是它恒返回 0，同账号重登永远救不回
 * 401 卡住的离线记账。
 *
 * 本文件负责锁住 **SyncWorker → DAO 写入侧**：
 * 1. 每条 failed 路径都经 [PendingTransactionDao.markFailed] 原子写入 reason；
 * 2. reason 精确传入（不是日志里打印一份、DB 里写另一份）；
 * 3. 失败路径**不再**调用 [PendingTransactionDao.updateSyncStatus]；
 * 4. 遇第一条 401 立即中断，不再对剩余 pending 重复发请求。
 *
 * 写入侧与重置侧的衔接（写入的行能否被 resetFailedMatching 命中）由
 * `PendingTransactionDaoMarkFailedTest` 端到端覆盖。
 *
 * 通过把 syncTransaction 改 internal 来单元测试关键分支，
 * 避免 Robolectric + WorkManager 集成测试的复杂度。
 */
class SyncWorkerSyncTransactionTest {

    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk()
    private val syncLock: SyncLock = mockk(relaxed = true)
    private val appLogger: com.aibill.android.util.AppLogger = mockk(relaxed = true)
    private val tokenManager: com.aibill.android.data.remote.interceptor.TokenManager = mockk(relaxed = true)

    /**
     * R-Z 守卫（[SyncWorker.doWork] 在 POST 前复查行是否还在）会让所有到达 POST 的
     * 既有用例多一次 `findByClientId`。默认桩在这里打上「行还在」，
     * 既有 25 条不需逐条补桩就能恢复；新增的「已删」用例在自己的 `runTest` 里
     * 用 `coEvery { ... returns null }` 覆盖它（后注册的桩优先）。
     *
     * 放 `@Before` 而非用例内：mock 实例由字段初始化器逐用例重建，
     * 桩的生命周期与用例一致，不会跨用例泄漏。
     */
    @BeforeEach
    fun setUpDefaultStubs() {
        coEvery { pendingDao.findByClientId(any()) } coAnswers { makeEntity(firstArg()) }
    }

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
    /** 默认视为「已登录」，让绝大多数同步用例直接进入同步流程 */
    private fun makeWorker(hasToken: Boolean = true): SyncWorker {
        every { tokenManager.hasToken() } returns hasToken
        return SyncWorker(
            appContext = mockk(relaxed = true),
            workerParams = mockk(relaxed = true),
            pendingTransactionDao = pendingDao,
            transactionApi = transactionApi,
            syncLock = syncLock,
            appLogger = appLogger,
            tokenManager = tokenManager,
        )
    }

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
        // markSynced 必须显式 stub 返回 1：relaxed mock 默认返回 0，
        // 会被误判成「用户已撤销」而触发补偿删除（TOCTOU 补偿用例的断言前提）。
        coEvery {
            pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any())
        } returns 1

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
            pendingDao.markFailed(
                clientId = "C1",
                error = SyncWorker.ERROR_SERVER_ANOMALY,
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `doWork - 401 seen - markFailed with EXACT token-expired reason, never updateSyncStatus`() = runTest {
        // Phase 0 核心回归：reason 必须原子落进 last_error。
        // 若这里退回 updateSyncStatus(status="failed")，本用例会红——
        // 因为 relaxed mock 会让「没调用 markFailed」的路径在旧写法下同样「看起来」正常。
        val httpEx = HttpException(Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.failure(), result)
        // (1) 走原子失败写入
        coVerify(exactly = 1) {
            pendingDao.markFailed(
                clientId = "C1",
                error = SyncWorker.ERROR_TOKEN_EXPIRED,
                updatedAt = any(),
            )
        }
        // (2) 旧的不写 last_error 的失败路径不得再被使用
        coVerify(exactly = 0) { pendingDao.updateSyncStatus(any(), any(), any()) }
        // (3) reason 精确值（防止有人改成别的字面量而悄悄断掉
        //     与 ERROR_TOKEN_EXPIRED_PATTERN 的配对）
        assertEquals("%Token expired%", SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN)
        assertEquals(true, SyncWorker.ERROR_TOKEN_EXPIRED.contains("Token expired"))
    }

    @Test
    fun `doWork - 401 on first pending - only ONE api call, rest stay pending, still returns failure`() = runTest {
        // 401 循环放大回归：旧实现只置 flag 不 break，N 条 pending 会发 N 次请求，
        // 且 AuthInterceptor 在第 1 个 401 就清了 session，剩下 N-1 条会被
        // 无差别标成 failed。正确语义：只请求 1 次，剩余保持 pending。
        val httpEx = HttpException(Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        coEvery { pendingDao.getAllPending() } returns listOf(
            makeEntity("C1"), makeEntity("C2"), makeEntity("C3"),
        )
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 3
        coEvery { transactionApi.createTransactions(any()) } throws httpEx

        val result = makeWorker().doWork()

        // (1) 只发一次请求
        coVerify(exactly = 1) { transactionApi.createTransactions(any()) }
        // (2) 只有第一条被标 failed，其余两条既不 failed 也不 synced（保持 pending）
        coVerify(exactly = 1) { pendingDao.markFailed("C1", any(), any()) }
        coVerify(exactly = 0) { pendingDao.markFailed("C2", any(), any()) }
        coVerify(exactly = 0) { pendingDao.markFailed("C3", any(), any()) }
        coVerify(exactly = 0) { pendingDao.markSynced(any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.incrementRetryCount(any(), any(), any()) }
        // (3) 仍然返回 failure 等用户重登
        assertEquals(androidx.work.ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork - 401 does NOT abort syncLock release`() = runTest {
        // 中断遍历不能绕过 finally，否则后续 login 会死等 syncLock
        val httpEx = HttpException(Response.error<Any>(401, okhttp3.ResponseBody.create(null, "")))
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"), makeEntity("C2"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 2
        coEvery { transactionApi.createTransactions(any()) } throws httpEx
        every { syncLock.acquire() } returns false

        makeWorker().doWork()

        io.mockk.verifyOrder {
            syncLock.acquire()
            syncLock.release()
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
            pendingDao.markFailed(
                clientId = "C1",
                error = SyncWorker.ERROR_MAX_RETRY_EXCEEDED,
                updatedAt = any(),
            )
        }
        // 不可恢复失败绝不能被 ERROR_TOKEN_EXPIRED_PATTERN 命中，
        // 否则同账号重登会把它无限重置成 pending 死循环。
        assertEquals(false, SyncWorker.ERROR_MAX_RETRY_EXCEEDED.contains("Token expired"))
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
                error = SyncWorker.ERROR_NETWORK,
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
            pendingDao.markFailed(
                clientId = "C1",
                error = SyncWorker.ERROR_BUSINESS,
                updatedAt = any(),
            )
        }
        coVerify(exactly = 0) { pendingDao.incrementRetryCount(any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.updateSyncStatus(any(), any(), any()) }
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
        coEvery { pendingDao.markSynced(any(), 99, any(), any()) } returns 1

        makeWorker().doWork()

        coVerify(exactly = 1) { pendingDao.markSynced("C1", 99, "synced", any()) }
        coVerify(exactly = 1) {
            pendingDao.markFailed("C2", SyncWorker.ERROR_SERVER_ANOMALY, any())
        }
    }

    @Test
    fun `no token - returns success WITHOUT touching pending queue (NetworkMonitor startup guard)`() = runTest {
        // 回归：NetworkMonitor 改为在 App 启动时排同步后，未登录也会触发 doWork。
        // 若不加 token 守卫，每条 pending 都会拿到 401 被标成 failed，
        // 而它们本来可以在用户重新登录后继续上传。
        val entity = makeEntity("C1")
        coEvery { pendingDao.getAllPending() } returns listOf(entity)

        val result = makeWorker(hasToken = false).doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // 关键：完全不碰队列
        coVerify(exactly = 0) { pendingDao.getAllPending() }
        coVerify(exactly = 0) { pendingDao.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.updateSyncStatus(any(), any(), any()) }
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
        // 也不占锁（避免把登录流程卡住）
        coVerify(exactly = 0) { syncLock.acquire() }
    }

    // ==================== TOCTOU 撤销补偿（方案 B）====================

    /**
     * 真值表：`markSynced` 返回的受影响行数是「本地这条 pending 是否还在」的唯一证据。
     *
     * | markSynced 影响行数 | serverId | 含义 | 动作 |
     * |---|---|---|---|
     * | 1 | 有 | 正常同步完成 | 只写 synced |
     * | 0 | 有 | **用户已撤销本地**（服务端已建） | 补偿删除服务端；成功再删残留本地行 |
     * | 0/1 | 无 | 无从补偿（降级路径） | 沿用 updateSyncStatus，不动服务端 |
     */

    /** 构造一个「pending 行已被删（返回 0）+ 服务端已建好」的 worker 场景。 */
    private suspend fun stubUndoRace(serverId: Int = 99) {
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = serverId, clientId = "C1")),
        )
        coEvery {
            pendingDao.markSynced(clientId = "C1", serverId = serverId, status = any(), updatedAt = any())
        } returns 0
    }

    private fun deleteResponse(code: Int) =
        ApiResponse<Any>(code = code, data = null, message = if (code == 0) "ok" else "删除失败")

    @Test
    fun `TOCTOU - markSynced updated the row so no compensating delete is issued`() = runTest {
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = 99, clientId = "C1")),
        )
        coEvery {
            pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any())
        } returns 1

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // 正常路径绝不能多发一个 DELETE：会误删用户没撤销的账。
        coVerify(exactly = 0) { transactionApi.deleteTransaction(any()) }
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
    }

    @Test
    fun `TOCTOU - markSynced affected 0 rows so the server record is deleted`() = runTest {
        stubUndoRace(serverId = 99)
        coEvery { transactionApi.deleteTransaction(99) } returns deleteResponse(code = 0)

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { transactionApi.deleteTransaction(99) }
    }

    @Test
    fun `TOCTOU - compensating delete succeeds - leftover local row is cleaned up`() = runTest {
        stubUndoRace(serverId = 99)
        coEvery { transactionApi.deleteTransaction(99) } returns deleteResponse(code = 0)

        makeWorker().doWork()

        coVerify(exactly = 1) { pendingDao.deleteByClientId("C1") }
        io.mockk.verify { appLogger.info(any(), match { it.contains("撤销补偿") }) }
    }

    @Test
    fun `TOCTOU - compensating delete fails - keeps local row, logs error, doWork survives`() = runTest {
        stubUndoRace(serverId = 99)
        coEvery { transactionApi.deleteTransaction(99) } returns deleteResponse(code = 5001)

        val result = makeWorker().doWork()

        // 补偿失败不能拖垮整轮同步，也不能把「没删成」写成成功。
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { pendingDao.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.updateSyncStatus(any(), any(), any()) }
        // 证据：日志里必须同时带上 clientId 和 serverId，事后才能人工对账。
        io.mockk.verify {
            appLogger.error(any(), match { it.contains("clientId=C1") && it.contains("serverId=99") })
        }
    }

    @Test
    fun `TOCTOU - compensating delete throws - doWork survives and still processes rest of queue`() = runTest {
        val e1 = makeEntity("C1")
        val e2 = makeEntity("C2")
        coEvery { pendingDao.getAllPending() } returns listOf(e1, e2)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returnsMany listOf(
            makeCreateResponse(created = listOf(transactionDto(id = 99, clientId = "C1"))),
            makeCreateResponse(created = listOf(transactionDto(id = 100, clientId = "C2"))),
        )
        // C1：用户已撤销（0 行）→ 补偿删除，但接口抛异常
        coEvery { pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any()) } returns 0
        // C2：正常同步
        coEvery { pendingDao.markSynced(clientId = "C2", serverId = 100, status = any(), updatedAt = any()) } returns 1
        coEvery { transactionApi.deleteTransaction(99) } throws IOException("network down")

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
        // 关键：异常不能中断遍历，C2 依旧被同步。
        coVerify(exactly = 1) {
            pendingDao.markSynced(clientId = "C2", serverId = 100, status = any(), updatedAt = any())
        }
        io.mockk.verify {
            appLogger.error(any(), match { it.contains("撤销补偿异常") && it.contains("serverId=99") })
        }
    }

    @Test
    fun `TOCTOU - compensating delete cancellation propagates, is not swallowed as error`() = runTest {
        // 补偿是挂起调用：worker 被取消（换号 awaitSyncIdle / 系统回收）时，
        // CancellationException 必须正常传播，而不是被当成「补偿异常」吞掉。
        val entity = makeEntity("C1")
        coEvery { pendingDao.getAllPending() } returns listOf(entity)
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = 99, clientId = "C1")),
        )
        coEvery { pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any()) } returns 0
        coEvery {
            transactionApi.deleteTransaction(99)
        } throws kotlinx.coroutines.CancellationException("cancelled")

        var propagated = false
        try {
            makeWorker().doWork()
        } catch (_: kotlinx.coroutines.CancellationException) {
            propagated = true
        }

        assertEquals(true, propagated)
        // 取消不得被降级成「撤销补偿异常」error 日志，也不得清本地行。
        io.mockk.verify(exactly = 0) {
            appLogger.error(any(), match { it.contains("撤销补偿异常") })
        }
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
    }

    // ==================== R-Z：快照后、POST 前的「行已被撤销」窗口 ====================

    /**
     * 与 R-Y 补偿互补的真值表（前者关「POST 后、回写前」，后者关「POST 前」）：
     *
     * | 发送前 findByClientId | 行为 | 标账 |
     * |---|---|---|
     * | null（已被撤销） | 跳过，不 POST | 不 markFailed / 不 markSynced / 不计失败 |
     * | 非空 | 照常 POST | 走既有 R-Y 结果分支 |
     *
     * 刻意不看 `syncStatus`：此刻只要行还在，该发就发，多一个状态判断就是死代码。
     */

    @Test
    fun `R-Z - row deleted after snapshot - skips POST without marking anything`() = runTest {
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        // 覆盖 @Before 的默认桩：用户在快照与 POST 之间点了撤销。
        coEvery { pendingDao.findByClientId("C1") } returns null

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
        coVerify(exactly = 0) { pendingDao.markFailed(any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.markSynced(any(), any(), any(), any()) }
        // 没 POST 就谈不上补偿，更不能顺手删别的行。
        coVerify(exactly = 0) { transactionApi.deleteTransaction(any()) }
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
        io.mockk.verify { appLogger.warn(any(), match { it.contains("行已被撤销删除") }) }
    }

    @Test
    fun `R-Z - row still present - POST issued exactly once (guard not inverted)`() = runTest {
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        coEvery { pendingDao.findByClientId("C1") } returns makeEntity("C1")
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = 99, clientId = "C1")),
        )
        coEvery {
            pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any())
        } returns 1

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // 守卫写反（非空才跳过）会让这条立刻红。
        coVerify(exactly = 1) { transactionApi.createTransactions(any()) }
        coVerify(exactly = 1) {
            pendingDao.markSynced(clientId = "C1", serverId = 99, status = any(), updatedAt = any())
        }
        coVerify(exactly = 0) { transactionApi.deleteTransaction(any()) }
    }

    @Test
    fun `R-Z - mixed queue - only the surviving row is POSTed`() = runTest {
        coEvery { pendingDao.getAllPending() } returns listOf(makeEntity("C1"), makeEntity("C2"))
        coEvery { pendingDao.getAnyUnsyncedCount() } returns 0
        // C1 被撤销，C2 仍存活（C2 走 @BeforeEach 默认桩返回非空实体）
        coEvery { pendingDao.findByClientId("C1") } returns null
        coEvery { transactionApi.createTransactions(any()) } returns makeCreateResponse(
            created = listOf(transactionDto(id = 100, clientId = "C2")),
        )
        coEvery {
            pendingDao.markSynced(clientId = "C2", serverId = 100, status = any(), updatedAt = any())
        } returns 1

        val result = makeWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { transactionApi.createTransactions(any()) }
        // 被删那条不进入任何状态转移；没被删那条照常入账。
        coVerify(exactly = 0) { pendingDao.markSynced(clientId = "C1", any(), any(), any()) }
        coVerify(exactly = 0) { pendingDao.markFailed("C1", any(), any()) }
        coVerify(exactly = 1) {
            pendingDao.markSynced(clientId = "C2", serverId = 100, status = any(), updatedAt = any())
        }
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
    }
}
