package com.aibill.android.service

import android.app.Application
import android.content.Context
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * `AutoRecordActionReceiver.handleUndo` 的状态真值表测试。
 *
 * ## 为什么这条链路必须有测试
 *
 * 撤销是 App 里**唯一**一个「一句话就让用户以为账已经消失」的入口，而它的正确性
 * 取决于本地行的 `sync_status` —— 该列写在 `PendingTransactionDao` 里，却从未被
 * 撤销逻辑读取过。历史实现只判「本地行是否存在」：
 *
 * ```
 *   findByClientId(clientId) != null → 只删本地 → Toast「已撤销」
 * ```
 *
 * 而 `cleanSyncedBefore` 定义在 DAO 里却**零调用点**，synced 行无限留存，
 * 于是「已上传到服务端的账」几乎总是走上面这条分支：服务端账目原封不动，
 * 用户却被告知已撤销 → 用户手动重记 → 重复账。已同步时用
 * `TransactionQuery(keyword = clientId)` + `items.first()` 猜 serverId 还有第二个
 * 风险：无排序保证、只取第 1 页、命中多条时删错账。
 *
 * 现在只认 `server_transaction_id`；宁可失败提示手动删除，也不猜、不谎报。
 *
 * ## `failed` 必须区分 reason（fail-safe 白名单）
 *
 * `SyncWorker` 的 markFailed 有四条路径，只有 401 能证明服务端拒绝、未落库：
 * `ERROR_SERVER_ANOMALY`（服务端可能已创建、只是响应没匹配上 clientId）、
 * `ERROR_MAX_RETRY_EXCEEDED`（上次请求可能已成功、只是响应丢了）、
 * `ERROR_BUSINESS`（5xx）以及 `lastError == null`（历史遗留写法）——
 * 这些都**不能**删本地，否则服务端留一笔用户看不见的幽灵账。
 * 未知 `syncStatus` 同理：`!= "synced"` 这种判据是 fail-open，
 * 将来加状态/改列名时会静默退化成「当成没上传→删本地」，必须落 `else` 分支不删。
 *
 * ## 本测试锁住的不变量
 *
 * 1. **Toast 是结果，不是意图**：说「已撤销」的前提是 DAO 或服务端**真的**删了；
 *    任何失败路径都不得出现「已撤销」，也不得出现「回收站」（pending 记录从未上过
 *    服务端，不存在回收站可恢复）。
 * 2. **synced 分支必须先服务端、后本地**：服务端失败时本地行必须留存，
 *    否则用户在流水页再也看不到这笔、也无从重试。
 * 3. **keyword 兜底已删除**：synced 路径 `getTransactions` 调用数恒为 0。
 *    这条断言是 mutation 式防线——把实现改回搜索分支，synced 的三条用例会立刻红。
 *
 * 直接调 `handleUndo`（internal）而不是走 `onReceive`：绕开 `goAsync()` +
 * 进程级 ApplicationScope 的时序噪声。注入字段是 `lateinit`，测试直接赋值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRecordActionReceiverUndoTest {

    private lateinit var context: Context
    private lateinit var pendingTransactionDao: PendingTransactionDao
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var appLogger: AppLogger
    private lateinit var receiver: AutoRecordActionReceiver

    private val clientId = "C-undo-1"
    private val serverId = 4242

    private companion object {
        const val MSG_UNDONE = "已撤销"
        const val MSG_FAILED_MANUAL = "撤销失败，请稍后在流水页手动删除"
        const val MSG_NOT_FOUND = "撤销失败，记录未找到"
    }

    @Before
    fun setUp() {
        // handleUndo 末尾会 withContext(Dispatchers.Main) 弹 Toast，
        // 必须替换 Main dispatcher，否则在 JVM 测试里抛
        // "Module with the Main dispatcher had failed to initialize"。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = RuntimeEnvironment.getApplication()
        pendingTransactionDao = mockk(relaxed = true)
        transactionRepository = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)

        receiver = AutoRecordActionReceiver()
        receiver.pendingTransactionDao = pendingTransactionDao
        receiver.transactionRepository = transactionRepository
        receiver.appLogger = appLogger
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun localEntity(
        syncStatus: String,
        serverTransactionId: Int? = null,
        lastError: String? = null,
    ) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        time = "12:30",
        source = "app_notification",
        clientCreatedAt = "2026-07-05T12:30:00Z",
        syncStatus = syncStatus,
        serverTransactionId = serverTransactionId,
        lastError = lastError,
    )

    private fun latestToast(): String? = ShadowToast.getTextOfLatestToast()

    // pending 表示尚未确认同步成功；本用例锁住无并发 Worker 时的既有本地撤销语义。
    // 「请求已在途但尚未回写 synced」的 TOCTOU 由独立补偿切片处理。

    @Test
    fun `pending - deletes local row only, no server call, reports undone`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns localEntity("pending")

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 1) { pendingTransactionDao.deleteByClientId(clientId) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_UNDONE, latestToast())
        // pending 记录从未上过服务端，谈不上「回收站可恢复」——不许出现。
        assertFalse(latestToast().orEmpty().contains("回收站"))
    }

    // ==================== failed：只有 401 能证明服务端没建 ====================

    @Test
    fun `failed with token expired - server rejected the request, deletes local row only`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("failed", lastError = SyncWorker.ERROR_TOKEN_EXPIRED)

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 1) { pendingTransactionDao.deleteByClientId(clientId) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_UNDONE, latestToast())
        assertFalse(latestToast().orEmpty().contains("回收站"))
    }

    @Test
    fun `failed with server anomaly - server may already have created it, deletes nothing`() = runTest {
        // SERVER_ANOMALY = 服务端返回里 created[]/duplicates[] 都没匹配上 clientId，
        // 交易**很可能已经在服务端**。删本地 = 用户永久看不见这笔账。
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("failed", lastError = SyncWorker.ERROR_SERVER_ANOMALY)

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    @Test
    fun `failed with max retry exceeded - last request may have succeeded, deletes nothing`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("failed", lastError = SyncWorker.ERROR_MAX_RETRY_EXCEEDED)

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    @Test
    fun `failed with null last error - no evidence about server state, deletes nothing`() = runTest {
        // 历史上 markFailed 误用 updateSyncStatus 不写 last_error，会留下这种行。
        // 「原因未知」不能当成「服务端一定没有」。
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("failed", lastError = null)

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    // ==================== 未知 status：fail-closed ====================

    @Test
    fun `unknown sync status - deletes nothing instead of treating it as unsynced`() = runTest {
        // `!= "synced"` 这类 fail-open 判据会把任何新状态当成「未上传→删本地」。
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("uploading_v2")

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    // ==================== synced + 有 serverId：先服务端，成功才删本地 ====================

    @Test
    fun `synced with serverId - server delete succeeds then local row removed, reports undone`() =
        runTest {
            coEvery { pendingTransactionDao.findByClientId(clientId) } returns
                localEntity("synced", serverTransactionId = serverId)
            coEvery { transactionRepository.deleteTransaction(serverId) } returns Result.Success(Unit)

            receiver.handleUndo(context, clientId)

            coVerify(exactly = 1) { transactionRepository.deleteTransaction(serverId) }
            // 顺序不变量：服务端删成功才敢删本地；反过来会把账变成两处都查不到。
            coVerifyOrder {
                transactionRepository.deleteTransaction(serverId)
                pendingTransactionDao.deleteByClientId(clientId)
            }
            // keyword 兜底已删除：synced 路径不再靠搜索猜 serverId。
            coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
            assertEquals(MSG_UNDONE, latestToast())
        }

    @Test
    fun `synced with serverId - server delete fails keeps local row and never claims undone`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("synced", serverTransactionId = serverId)
        coEvery {
            transactionRepository.deleteTransaction(serverId)
        } returns Result.Error(5001, "删除失败")

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 1) { transactionRepository.deleteTransaction(serverId) }
        // 服务端还在，本地行必须留着，用户才能在流水页看到并重试。
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    @Test
    fun `synced with serverId - non success result (Loading) treated as failure`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("synced", serverTransactionId = serverId)
        coEvery { transactionRepository.deleteTransaction(serverId) } returns Result.Loading

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 1) { transactionRepository.deleteTransaction(serverId) }
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
    }

    // ==================== synced 但无 serverId：无处可删，诚实报失败 ====================

    @Test
    fun `synced without serverId - deletes nothing on either side, reports failure`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("synced", serverTransactionId = null)

        receiver.handleUndo(context, clientId)

        // 没有可靠凭据定位服务端那条 → 既不猜（无搜索）、也不删本地。
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_FAILED_MANUAL, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }

    // ==================== 本地无此行 ====================

    @Test
    fun `local row missing - deletes nothing, reports not found`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns null

        receiver.handleUndo(context, clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(MSG_NOT_FOUND, latestToast())
        assertFalse(latestToast().orEmpty().contains(MSG_UNDONE))
    }
}
