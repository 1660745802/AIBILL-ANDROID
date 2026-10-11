package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.service.SyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `observeSyncStatus()` 聚合查询的**真实 Room** 测试。
 *
 * ## 为什么单独一个文件（而不是并进 PendingTransactionDaoMarkFailedTest）
 *
 * 那个文件的主题是「401 恢复链路的写入→重置不变量」，本文件的主题是
 * 「横幅计数的分区语义」。两者共用同一套 harness（Robolectric + inMemory Room），
 * 但断言互不相干：把统计塞进 401 主题文件只会稀释两边的可读性，
 * 让后人在改 401 逻辑时顺手看坏聚合语义。
 *
 * ## 为什么 mock 不了这一层
 *
 * 这是本切片唯一真正在写 SQL 的地方。用 mockk 打桩 `dao.observeSyncStatus()`
 * 只能证明「ViewModel 会转发一个 Flow」，**无法证明 SQL 数对了**——
 * 而历史事故（一个叫 unsynced、实际只数 pending 的计数方法）恰好就藏在
 * mock 覆盖不到的 SQL 里。三个用例各自对应一个可被变异破坏的点：
 *
 * - `1 pending + 1 failed + 1 synced → (1,1)`：pending 列若混入 failed 必红；
 * - `0 pending + 2 failed → (0,2)`：failed 列若被写死 0 必红；
 * - `空表 → (0,0)`：守住「聚合必返回一行、且两列是真 0」，这正是禁止 `GROUP BY`
 *   的理由（0 行时 Room 生成代码走 `else { _result = null }`，null 要到非空类型边界才炸）。
 *
 * ⚠️ **已实测**：把 `COUNT(CASE …)` 换成 `SUM(CASE …)` 不加 `COALESCE`，本文件**不会变红**。
 * Room 对非空 Int 生成的是裸 `_cursor.getInt(i)`（无 isNull 分支），而 Android `Cursor`
 * 对 NULL 返回 0——所以 SUM 靠一条游标实现细节兜住了。那条禁令因此**靠注释、不靠测试**，
 * 见 `PendingTransactionDao.observeSyncStatus` 的 KDoc。
 *
 * 本测试不新增任何依赖：Robolectric + room-testing 已在
 * `app/build.gradle.kts` 的 testImplementation 中。
 *
 * `application = Application::class`：默认 manifest 启动 `AiBillApp`，
 * 其 Hilt 图会构造 TokenManager → EncryptedSharedPreferences → AndroidKeyStore，
 * 在 Robolectric 沙箱里抛 `AndroidKeyStore not found`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PendingTransactionDaoQueueStatusTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingTransactionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        )
            // 不加 fallback：schema 不一致必须立刻失败而不是静默清表
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

    private suspend fun queueStatus() = dao.observeSyncStatus().first()

    /** synced 行必须被两列都排除：它既不排队也没失败。 */
    @Test
    fun `pending + failed + synced counts only the first two partitions`() = runTest {
        dao.insert(entity("P1")) // pending（默认）
        dao.insert(entity("F1"))
        dao.markFailed("F1", SyncWorker.ERROR_BUSINESS, updatedAt = 100L)
        dao.insert(entity("S1"))
        dao.markSynced("S1", serverId = 99)

        assertEquals(SyncQueueStatus(pending = 1, failed = 1), queueStatus())
    }

    /** 空表必须稳定返回 (0,0)：聚合必须恰好返回一行，而不是 0 行。 */
    @Test
    fun `empty table yields zero and zero`() = runTest {
        assertEquals(SyncQueueStatus(pending = 0, failed = 0), queueStatus())
        assertEquals(true, queueStatus().isEmpty)

        // 只有 synced 之后清空，仍是 (0,0)：synced 不属于任何待上传分区
        dao.insert(entity("S1"))
        dao.markSynced("S1", serverId = 99)
        assertEquals(SyncQueueStatus(pending = 0, failed = 0), queueStatus())
    }

    /** 全部失败：failed 分区必须真实计数，横幅才不会整体消失（历史缺口的形状）。 */
    @Test
    fun `all rows failed is counted instead of rendering as nothing to sync`() = runTest {
        dao.insert(entity("F1"))
        dao.insert(entity("F2"))
        dao.markFailed("F1", SyncWorker.ERROR_SERVER_ANOMALY, updatedAt = 100L)
        dao.markFailed("F2", SyncWorker.ERROR_MAX_RETRY_EXCEEDED, updatedAt = 101L)

        val status = queueStatus()
        assertEquals(SyncQueueStatus(pending = 0, failed = 2), status)
        // 两分区并存时互不吞并
        assertEquals(false, status.isEmpty)

        // 与既有口径对齐：failed 会计入 getAnyUnsyncedCount，但**不会**被自动续传捞起
        assertEquals(2, dao.getAnyUnsyncedCount())
        assertEquals(0, dao.getAllPending().size)
    }

    /**
     * 聚合的 pending 列必须与 `observePendingCount()` 的 SQL **同口径**。
     * 两者一旦分岔，横幅的标题数字与显隐会互相矛盾。
     */
    @Test
    fun `aggregate pending column matches observePendingCount`() = runTest {
        dao.insert(entity("P1"))
        dao.insert(entity("P2"))
        dao.insert(entity("F1"))
        dao.markFailed("F1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)

        val fromAggregate = queueStatus().pending
        val fromSingle = dao.observePendingCount().first()

        assertEquals(fromSingle, fromAggregate)
        assertEquals(2, fromAggregate)
        assertEquals(1, queueStatus().failed)
    }
}
