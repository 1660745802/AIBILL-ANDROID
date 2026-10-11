package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.service.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 401 恢复链路的**端到端不变量**测试（写入侧 → 重置侧）。
 *
 * ## 为什么必须有一个真实 Room 的测试
 *
 * 这条不变量横跨两个 mock 边界，而现有测试**两侧都 mock 掉了中间**：
 *
 * ```
 *   SyncWorker.markFailed           AuthRepositoryImpl.onSameAccountRelogin
 *          │                                       │
 *          │ dao.markFailed(...)     dao.resetFailedMatching("%Token expired%")
 *          ▼                                       ▼
 *      ┌───────────────────────────────────────────────┐
 *      │  同一张表的同一列：last_error                  │  ← 真实事故就发生在这里
 *      └───────────────────────────────────────────────┘
 * ```
 *
 * `SyncWorkerSyncTransactionTest` 只能断言「调用了 markFailed 且 reason 正确」，
 * `AuthRepositoryImplTest` 只能断言「调用了 resetFailedMatching」。
 * **没有任何一个测试验证前者写入的值能被后者的 WHERE 命中。**
 *
 * 历史 bug 正是落在这个缝里：`markFailed` 误用 `updateSyncStatus`
 * （不写 `last_error`），两个 mock 测试都绿，但 `resetFailedMatching` 在生产中
 * 恒返回 0 —— 401 卡住的离线记账**永久无法上传且 UI 无感**。
 *
 * 只有把真实 DAO 摆进这个缝里跑一遍，缝才会被缝上。
 *
 * 本测试不新增任何依赖：Robolectric + room-testing 已在
 * `app/build.gradle.kts` 的 testImplementation 中。
 *
 * `application = Application::class` 是必需的：默认会按 manifest 启动真实的
 * `AiBillApp`，其 Hilt 图会构造 `TokenManager` → `EncryptedSharedPreferences`
 * → `AndroidKeyStore`，在 JVM/Robolectric 沙箱里抛 `KeyStoreException: AndroidKeyStore not found`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingTransactionDaoMarkFailedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingTransactionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        )
            // 不需要 migration：v7 已是当前版本；这里刻意不加 fallback，
            // 让 schema 不一致立刻失败而不是静默清表。
            .allowMainThreadQueries()
            .build()
        dao = db.pendingTransactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(clientId: String) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        source = "manual",
        clientCreatedAt = "2026-07-05T12:00:00Z",
    )

    /**
     * 核心不变量：SyncWorker 写入的 reason 必须能被
     * AuthRepositoryImpl 的重置模式命中。
     */
    @Test
    fun `markFailed writes last_error that resetFailedMatching can match`() = runTest {
        dao.insert(entity("C1"))

        // 写入侧：SyncWorker 走的就是这一步
        dao.markFailed("C1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)

        val afterFailure = dao.findByClientId("C1")!!
        assertEquals("failed", afterFailure.syncStatus)
        assertEquals(SyncWorker.ERROR_TOKEN_EXPIRED, afterFailure.lastError)
        assertEquals(100L, afterFailure.updatedAt)

        // 读取侧：AuthRepositoryImpl 走的就是这一步
        val reset = dao.resetFailedMatching(SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN, now = 200L)

        assertEquals("401 失败必须能被重置，否则离线记账永久卡死", 1, reset)

        val afterReset = dao.findByClientId("C1")!!
        assertEquals("pending", afterReset.syncStatus)
        assertNull(afterReset.lastError)
        // 复活后必须重新可被 SyncWorker 捞起，否则 reset 也没意义
        assertEquals(listOf("C1"), dao.getAllPending().map { it.clientId })
    }

    /**
     * 不可恢复失败**不得**被 401 模式复活。
     * 若这条变红，说明有人的 reason 里混进了 "Token expired" 子串，
     * 同账号重登会把永久失败的记录重置成无限重试死循环。
     */
    @Test
    fun `permanent failures are NOT revived by the token-expired pattern`() = runTest {
        val permanentReasons = listOf(
            SyncWorker.ERROR_MAX_RETRY_EXCEEDED,
            SyncWorker.ERROR_SERVER_ANOMALY,
            SyncWorker.ERROR_BUSINESS,
        )
        permanentReasons.forEachIndexed { i, reason ->
            dao.insert(entity("P$i"))
            dao.markFailed("P$i", reason, updatedAt = 100L)
        }

        val reset = dao.resetFailedMatching(SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN, now = 200L)

        assertEquals("不可恢复失败不得被 401 模式复活", 0, reset)
        permanentReasons.forEachIndexed { i, reason ->
            val row = dao.findByClientId("P$i")!!
            assertEquals("failed", row.syncStatus)
            assertEquals(reason, row.lastError)
        }
    }

    /**
     * 单条 UPDATE 的原子性守卫：失败写入不得留下
     * `sync_status='failed'` 且 `last_error IS NULL` 的行——
     * 那正是本次修复前的老状态，也是 `resetFailedMatching` 匹配不上的原因。
     */
    @Test
    fun `markFailed never leaves a failed row with null last_error`() = runTest {
        listOf(
            SyncWorker.ERROR_TOKEN_EXPIRED,
            SyncWorker.ERROR_MAX_RETRY_EXCEEDED,
            SyncWorker.ERROR_SERVER_ANOMALY,
            SyncWorker.ERROR_BUSINESS,
        ).forEachIndexed { i, reason ->
            dao.insert(entity("A$i"))
            dao.markFailed("A$i", reason, updatedAt = 100L)

            val row = dao.findByClientId("A$i")!!
            assertEquals("failed", row.syncStatus)
            assertEquals(
                "failed 行必须带 reason（$reason）",
                reason,
                row.lastError,
            )
        }

        assertEquals(
            "任何一条 failed 都没有 last_error —— 就是老 bug 的形状",
            0,
            dao.observeFailedTransactions().first().count { it.lastError.isNullOrBlank() },
        )
    }

    /**
     * 网络错误路径走 incrementRetryCount：只累加 retry_count，
     * **不改** sync_status，因此这类记录仍是 pending、可继续被捞起。
     */
    @Test
    fun `incrementRetryCount keeps row pending so worker can pick it up again`() = runTest {
        dao.insert(entity("N1"))

        dao.incrementRetryCount("N1", SyncWorker.ERROR_NETWORK, updatedAt = 100L)

        val row = dao.findByClientId("N1")!!
        assertEquals("pending", row.syncStatus)
        assertEquals(1, row.retryCount)
        assertEquals(SyncWorker.ERROR_NETWORK, row.lastError)
        assertEquals(listOf("N1"), dao.getAllPending().map { it.clientId })
    }

    /**
     * 失败后仍计入 getAnyUnsyncedCount()：SyncWorker 靠它决定
     * 返回 retry() 还是 success()。若这个计数漏掉 failed，
     * 队列会被误判为「全部成功」而停止调度。
     *
     * 同时固定两个容易误改的语义：
     * - `getAnyUnsyncedCount()` 含 failed（**清空前的警告口径**，
     *   `SyncQueue.unsyncedCount` 委托的就是它）；
     * - `getAllPending()` **不含** failed（**排队等上传的口径**，横向幅读它）。
     *
     * ⚠️ 这里曾是「Home 横幅看不到失败行」的可观测性缺口，现已由
     * `SyncQueueStatus`（`PendingTransactionDaoQueueStatusTest` 锁）在**另一条查询**里补齐。
     *
     * 📌 收口前本用例还与 `getPendingCount()`（生产零调用）对拍，钉住
     * 「pending-only ≠ unsynced」。该方法已在架构候选 3 删除——
     * pending-only 的唯一读法现在是 `observeSyncStatus()` 的 pending 列，
     * 由上面那条 QueueStatus 测试以真实 SQL 钉死。
     */
    @Test
    fun `failed rows still count as unsynced`() = runTest {
        dao.insert(entity("U1"))
        dao.insert(entity("U2"))
        dao.markFailed("U1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)

        // U1 failed + U2 pending = 2（unsynced 口径含 failed）
        assertEquals(2, dao.getAnyUnsyncedCount())
        // 但排队等上传的只有 U2（pending 口径不计 failed）
        assertEquals(listOf("U2"), dao.getAllPending().map { it.clientId })

        // U1 复活后重新计入两个口径
        assertEquals(1, dao.resetFailedMatching(SyncWorker.ERROR_TOKEN_EXPIRED_PATTERN, now = 200L))
        assertEquals(2, dao.getAllPending().size)
        assertEquals(2, dao.getAnyUnsyncedCount())
    }
}
