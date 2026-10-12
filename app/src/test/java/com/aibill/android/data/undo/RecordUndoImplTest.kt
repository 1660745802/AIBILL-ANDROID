package com.aibill.android.data.undo

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.domain.undo.UndoOutcome
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 撤销 module（[RecordUndoImpl]）的状态真值表测试。
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
 * 而本地缓存没有任何清理窗口，synced 行无限留存，
 * 于是「已上传到服务端的账」几乎总是走上面这条分支：服务端账目原封不动，
 * 用户却被告知已撤销 → 用户手动重记 → 重复账。已同步时用
 * `TransactionQuery(keyword = clientId)` + `items.first()` 猜 serverId 还有第二个
 * 风险：无排序保证、只取第 1 页、命中多条时删错账。
 *
 * 现在只认 `server_transaction_id`；宁可失败提示手动删除，也不猜、不谎报。
 *
 * ## 缓存行只有一种合法形态：`synced`
 *
 * 新写入的行都在**服务端确认之后**（`RecordIntakeImpl.confirmAndCache`），
 * 一律带 `server_transaction_id`。老版本留下的 `pending` / `failed` 行没有对应的
 * 确认事实：可能从没上过服务端，也可能上过（响应丢了）——**两种都不能删**。
 * 因此判据是白名单：只有 `synced` 才动手，其余落 `else` 分支什么都不删。
 * 用 `!= "synced"` 写就成了 fail-open：将来加状态 / 改列名会静默退化成
 * 「当成没上传 → 删本地」，把服务端那笔变成用户看不见的幽灵账。
 *
 * ## 本测试锁住的不变量
 *
 * 1. **文案是结果，不是意图**：说「已撤销」的前提是 DAO 或服务端**真的**删了；
 *    任何失败路径都不得出现「已撤销」，也不得出现「回收站」（缓存行并非从回收站可恢复）。
 * 2. **synced 分支必须先服务端、后本地**：服务端失败时本地行必须留存，
 *    否则用户在流水页再也看不到这笔、也无从重试。
 * 3. **keyword 兜底已删除**：synced 路径 `getTransactions` 调用数恒为 0。
 *    这条断言是 mutation 式防线——把实现改回搜索分支，synced 的用例会立刻红。
 *
 * ## 失败文案分两类，且测试里的字符串是**有意双写**
 *
 * 硬约束：**不得指引用户无法执行的动作，也不得主张不确定的上传状态**。
 * 因此失败文案拆成两支：
 *
 * - `ServerStillHasIt`（服务端**确有**这笔）：指向流水页——流水页数据源是服务端，
 *   那里看得到也删得掉。断言必须 `contains("流水页")`，防「一刀切改保守」也是信息丢失。
 * - `ServerStateUnknown`（服务端**状态未知**）：什么都没删，不得指路、不得主张上传状态。
 *   断言用 `assertNoActionOrUploadClaim()` 挡禁词。
 *
 * 本类的 `MSG_*` 常量是生产 `UndoOutcome.userMessage` 的**字面量副本**（而非引用）：
 * 故意双写。只有这样，生产侧文案被无意识改动时全等断言才会红；若引用生产文案，
 * 改文案两边自动同步、测试永远绿。代价是改文案要同步改这里——接受。
 *
 * ⚠️ 全等断言的局限：若有人把生产与测试字面量**同时**改成违反约束的
 * （例：「尚未上传到服务器」），全等断言仍会绿——**只有 `assertNoActionOrUploadClaim`
 * 的禁词断言能拦**。这也是约束断言必须存在的原因。
 *
 * ## 这里为什么不再需要 Robolectric
 *
 * 收口前判据住在 `AutoRecordActionReceiver.handleUndo`（`internal suspend fun`）里，
 * 用例因此要 `@RunWith(RobolectricTestRunner)` + `application = Application::class`，
 * 还要 `Dispatchers.setMain(UnconfinedTestDispatcher())` 顶掉末尾的 `Toast`。
 * 判据搬到 [RecordUndoImpl] 后这些都是噪声：**本类是纯 JVM 单测**，构造即用。
 * 接收器那边只剩「取 extras / 撤通知 / goAsync / 播 Toast」，属平台管道，不测。
 */
class RecordUndoImplTest {

    private lateinit var pendingTransactionDao: PendingTransactionDao
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var appLogger: AppLogger
    private lateinit var undo: RecordUndoImpl

    private val clientId = "C-undo-1"
    private val serverId = 4242

    private companion object {
        const val MSG_UNDONE = "已撤销"
        /** 与生产的 `UndoOutcome.ServerStillHasIt.userMessage` **有意双写**（理由见类 KDoc） */
        const val MSG_FAILED_MANUAL = "撤销失败，请稍后在流水页手动删除"

        /** 与生产的 `UndoOutcome.ServerStateUnknown.userMessage` **有意双写** */
        const val MSG_FAILED_UNCHANGED = "撤销失败，未删除任何数据"

        const val MSG_NOT_FOUND = "撤销失败，未找到本地记录"
    }

    @BeforeEach
    fun setUp() {
        pendingTransactionDao = mockk(relaxed = true)
        transactionRepository = mockk(relaxed = true)
        appLogger = mockk(relaxed = true)

        undo = RecordUndoImpl(
            pendingTransactionDao = pendingTransactionDao,
            transactionRepository = transactionRepository,
            appLogger = appLogger,
        )
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

    /**
     * 硬约束：服务端**状态未知**时的失败文案，
     * 既不得指引用户无法执行的动作（流水页 / 手动删除），
     * 也不得主张不确定的上传状态（上传 / 服务器）。
     */
    private fun assertNoActionOrUploadClaim(message: String) {
        listOf("流水页", "手动删除", "上传", "服务器").forEach { forbidden ->
            assertFalse(message.contains(forbidden), "状态未知的失败文案不得含「$forbidden」：$message")
        }
    }

    /** 任何非「已撤销」的结果都不得出现「已撤销」，也不得提「回收站」。 */
    private fun assertNotClaimedUndone(outcome: UndoOutcome) {
        assertFalse(outcome.userMessage.contains(MSG_UNDONE), outcome.userMessage)
        assertFalse(outcome.userMessage.contains("回收站"), outcome.userMessage)
    }

    // ==================== 非 synced：老版本遗留行一律 fail-closed ====================
    //
    // 这些行没有「服务端到底有没有这笔」的证据：`pending` 可能正在上传、也可能从没发出去，
    // `failed` 里的 401 也只是「那一次被拒」，本地重登后还可能再发。删本地就可能留下
    // 服务端看不见的幽灵账，因此一律什么都不删 + 如实说明。
    // 未知 status（`uploading_v2`）同样落 else：`!= "synced"` 这类 fail-open 判据
    // 会把任何新状态当成「未上传 → 删本地」，是白名单 `when` 明确挡掉的退化。

    @Test
    fun `non synced rows - fail closed regardless of status or recorded reason`() = runTest {
        val rows = listOf(
            localEntity("pending"),
            // 401 也救不了它：那次 401 之后本地并没有删行，服务端状态依然只能靠猜。
            localEntity("failed", lastError = "Token expired, need re-login"),
            localEntity("failed", lastError = "Server response missing clientId match (created/duplicates both empty)"),
            localEntity("failed", lastError = "Max retry count exceeded"),
            localEntity("failed", lastError = null),
            localEntity("uploading_v2"),
        )

        rows.forEach { row ->
            coEvery { pendingTransactionDao.findByClientId(clientId) } returns row

            val outcome = undo.undo(clientId)

            assertEquals(
                UndoOutcome.ServerStateUnknown,
                outcome,
                "status=${row.syncStatus} lastError=${row.lastError}",
            )
            assertEquals(MSG_FAILED_UNCHANGED, outcome.userMessage)
            assertNoActionOrUploadClaim(outcome.userMessage)
            assertNotClaimedUndone(outcome)
        }
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
    }

    // ==================== synced + 有 serverId：先服务端，成功才删本地 ====================

    @Test
    fun `synced with serverId - server delete succeeds then local row removed, reports undone`() =
        runTest {
            coEvery { pendingTransactionDao.findByClientId(clientId) } returns
                localEntity("synced", serverTransactionId = serverId)
            coEvery { transactionRepository.deleteTransaction(serverId) } returns Result.Success(Unit)

            val outcome = undo.undo(clientId)

            coVerify(exactly = 1) { transactionRepository.deleteTransaction(serverId) }
            // 顺序不变量：服务端删成功才敢删本地；反过来会把账变成两处都查不到。
            coVerifyOrder {
                transactionRepository.deleteTransaction(serverId)
                pendingTransactionDao.deleteByClientId(clientId)
            }
            // keyword 兜底已删除：synced 路径不再靠搜索猜 serverId。
            coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
            assertEquals(UndoOutcome.Undone, outcome)
            assertEquals(MSG_UNDONE, outcome.userMessage)
        }

    @Test
    fun `synced with serverId - any non success server result keeps the local row`() = runTest {
        listOf(
            Result.Error(5001, "删除失败"),
            Result.Loading,
        ).forEach { serverResult ->
            coEvery { pendingTransactionDao.findByClientId(clientId) } returns
                localEntity("synced", serverTransactionId = serverId)
            coEvery { transactionRepository.deleteTransaction(serverId) } returns serverResult

            val outcome = undo.undo(clientId)

            coVerify(atLeast = 1) { transactionRepository.deleteTransaction(serverId) }
            // 服务端还在，本地行必须留着，用户才能在流水页看到并重试。
            coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
            coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
            assertEquals(UndoOutcome.ServerStillHasIt, outcome, "result=$serverResult")
            assertEquals(MSG_FAILED_MANUAL, outcome.userMessage)
            // 服务端**确有**这笔 ⇒ 必须把处置入口指出来（防「一刀切改保守」也是信息丢失）
            assertTrue(outcome.userMessage.contains("流水页"))
            assertNotClaimedUndone(outcome)
        }
    }

    @Test
    fun `synced with serverId - cancellation propagates and the local row is kept`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("synced", serverTransactionId = serverId)
        coEvery { transactionRepository.deleteTransaction(serverId) } throws CancellationException("cancelled")

        var propagated: CancellationException? = null
        try {
            undo.undo(clientId)
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull(propagated, "取消必须原样抛出，不能被吞成 ServerStillHasIt")
        // fail-closed：取消时本地行必须留存
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
    }

    // ==================== synced 但无 serverId：无处可删，诚实报失败 ====================

    @Test
    fun `synced without serverId - deletes nothing on either side, reports failure`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns
            localEntity("synced", serverTransactionId = null)

        val outcome = undo.undo(clientId)

        // 没有可靠凭据定位服务端那条 → 既不猜（无搜索）、也不删本地。
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(UndoOutcome.ServerStillHasIt, outcome)
        assertEquals(MSG_FAILED_MANUAL, outcome.userMessage)
        // 服务端确有（synced 是 code==0 + clientId 匹配的产物）⇒ 指向流水页是可执行动作
        assertTrue(outcome.userMessage.contains("流水页"))
        assertNotClaimedUndone(outcome)
    }

    // ==================== 本地无此行 ====================

    @Test
    fun `local row missing - deletes nothing, reports not found`() = runTest {
        coEvery { pendingTransactionDao.findByClientId(clientId) } returns null

        val outcome = undo.undo(clientId)

        coVerify(exactly = 0) { pendingTransactionDao.deleteByClientId(any()) }
        coVerify(exactly = 0) { transactionRepository.deleteTransaction(any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(any()) }
        assertEquals(UndoOutcome.LocalMissing, outcome)
        assertEquals(MSG_NOT_FOUND, outcome.userMessage)
        // 「本地」是限定词：不能被读成「这笔从没记过」而诱发重复记账
        assertTrue(outcome.userMessage.contains("本地"))
        assertNotClaimedUndone(outcome)
    }
}
