package com.aibill.android.data.queue

import androidx.room.Room
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.queue.SyncRequest
import com.aibill.android.service.SyncWorker
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 离线队列 module（[com.aibill.android.domain.queue.SyncQueue]）的 interface 级测试。
 *
 * ## 为什么用真实 Room + 只 mock 掉 WorkManager
 *
 * 本 module 的价值全在**两件 mock 掉就看不见的事**上：
 * 1. 入队 / 恢复 / 清空落到同一张表后，快照的分区数字对不对；
 * 2. 401 的**判据配对**——写入侧写进去的 `last_error` 能不能被恢复侧的 WHERE 命中。
 * 用 mock DAO 打桩只能证明「某方法被调了」，这两件事都证明不了。
 *
 * WorkManager 则相反：Robolectric 里没有可用的 WorkManager 实例（且本工程不引
 * `work-testing`），所以 `SyncScheduler` 用 `mockkObject` 打桩。
 * 排调度的幂等性来自 `APPEND_OR_REPLACE` 策略本身，不在本层测。
 *
 * ## 迁移说明
 *
 * 本文件承接了已删除的 `TransactionRepositorySyncPendingTest`：它钉的两条契约
 * （① 排调度必被触发；② 排调度抛异常时**不抛给调用方**、而是可报告的失败）
 * 现在长在 `SyncQueue.requestSync()` 上，形状从 `Result<Unit>` 变成
 * `SyncRequest`（`Enqueued` / `Rejected`）。
 *
 * 不新增任何依赖：Robolectric + room-testing 已在 `testImplementation`。
 * `application = Application::class` 是必需的：默认会按 manifest 启动真实的
 * `AiBillApp`，其 Hilt 图会构造 `TokenManager` → `EncryptedSharedPreferences`
 * → `AndroidKeyStore`，在 Robolectric 沙箱里抛 `KeyStoreException`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncQueueImplTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingTransactionDao
    private lateinit var notificationDao: NotificationRecordDao
    private lateinit var queue: SyncQueueImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.pendingTransactionDao()
        notificationDao = db.notificationRecordDao()
        queue = SyncQueueImpl(dao, RuntimeEnvironment.getApplication())

        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(SyncScheduler)
        db.close()
    }

    private fun entity(clientId: String) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        source = "app_notification",
        clientCreatedAt = "2026-07-05T04:00:00Z",
    )

    private suspend fun snapshot() = queue.observeStatus().first()

    /** 入队 → 落在 pending 分区，且**不会**污染 failed 分区。 */
    @Test
    fun `enqueued rows land in the pending partition only`() = runTest {
        val writer = DirectRecordWriter(dao, notificationDao)

        writer.write(
            RecordWrite(
                pending = entity("Q1"),
                newRecord = NotificationRecordEntity(
                    packageName = "com.tencent.mm",
                    content = "支付成功 ¥32.00",
                    parsedAmount = 3200,
                    status = "confirmed",
                ),
            )
        )

        assertEquals(SyncQueueStatus(pending = 1, failed = 0), snapshot())
        assertEquals(1, queue.unsyncedCount())
    }

    /**
     * 401 恢复：行必须回到 **pending 分区**（而不只是「不再 failed」）。
     *
     * 判据配对在这一条里被端到端钉住：写入侧写 `SyncWorker.ERROR_TOKEN_EXPIRED`，
     * 恢复侧用 `SyncQueueImpl` 内部的 `ERROR_TOKEN_EXPIRED_PATTERN` 去命中。
     * 任何一侧改了字符串，这个用例立刻红。
     */
    @Test
    fun `recovering a token-expired row puts it back into the pending partition`() = runTest {
        dao.insert(entity("R1"))
        dao.insert(entity("R2"))
        dao.markFailed("R1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)
        assertEquals(SyncQueueStatus(pending = 1, failed = 1), snapshot())

        val revived = queue.recoverTokenExpiredFailures()

        assertEquals(1, revived)
        assertEquals(SyncQueueStatus(pending = 2, failed = 0), snapshot())
        // 复活必须真的能被 SyncWorker 捞起，否则 reset 没有意义
        assertEquals(listOf("R1", "R2"), dao.getAllPending().map { it.clientId })
    }

    /** 不可恢复的失败不得被 401 模式复活（否则重登即无限重试死循环）。 */
    @Test
    fun `permanent failures are not revived`() = runTest {
        dao.insert(entity("B1"))
        dao.markFailed("B1", SyncWorker.ERROR_BUSINESS, updatedAt = 100L)

        assertEquals(0, queue.recoverTokenExpiredFailures())
        assertEquals(SyncQueueStatus(pending = 0, failed = 1), snapshot())
    }

    /**
     * 口径：快照分两区、`unsyncedCount` 才合并，且**从不相加**。
     *
     * 这是两种提问：横幅问「有没有东西没传上去 / 哪部分要人管」，
     * 清空前的警告问「这次会带走多少用户数据」。混用会漏算 failed。
     */
    @Test
    fun `unsyncedCount covers both partitions while the snapshot keeps them apart`() = runTest {
        dao.insert(entity("C1"))
        dao.insert(entity("C2"))
        dao.insert(entity("C3"))
        dao.markFailed("C2", SyncWorker.ERROR_SERVER_ANOMALY, updatedAt = 100L)

        assertEquals(SyncQueueStatus(pending = 2, failed = 1), snapshot())
        assertEquals(3, queue.unsyncedCount())
    }

    /** 清空走的是整表 deleteAll，与 `AuthRepositoryImpl.clearLocalCache` 同一张表。 */
    @Test
    fun `clear empties the queue`() = runTest {
        dao.insert(entity("D1"))
        dao.insert(entity("D2"))

        queue.clear()

        assertEquals(SyncQueueStatus(pending = 0, failed = 0), snapshot())
        assertEquals(0, queue.unsyncedCount())
    }

    /**
     * 排调度必被触发，且**每次调用都真的交给 WorkManager**（不自己吞掉）。
     *
     * 幂等性来自 `APPEND_OR_REPLACE`：连排 N 次会入队 N 个 worker，
     * 第 2…N 个发现队列已空即早退，不造成重复上传。
     * 本层不能、也不该替 WorkManager 做去重。
     */
    @Test
    fun `requestSync always delegates to the WorkManager pipeline`() = runTest {
        assertEquals(SyncRequest.Enqueued, queue.requestSync())
        assertEquals(SyncRequest.Enqueued, queue.requestSync())

        io.mockk.verify(exactly = 2) { SyncScheduler.scheduleSyncIfNeeded(any()) }
    }

    /**
     * 迁移自 `TransactionRepositorySyncPendingTest` 的第二条契约：
     * WorkManager 抛异常时**不抛给调用方**，而是返回一个可报告的失败。
     *
     * 抛出去的后果很具体：入账侧异常会逃出 `viewModelScope`，而本工程没有全局
     * `CoroutineExceptionHandler` = 直接崩进程；首页侧则连「为什么失败」都无从上报。
     */
    @Test
    fun `requestSync reports a rejection instead of throwing`() {
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } throws RuntimeException("WM error")

        val request = queue.requestSync()

        assertTrue(request is SyncRequest.Rejected)
        assertEquals("WM error", (request as SyncRequest.Rejected).cause.message)
    }

    /** 排调度失败与队列数据无关：快照不受影响，账也不会被回滚。 */
    @Test
    fun `a rejected request leaves the queue snapshot untouched`() = runTest {
        dao.insert(entity("E1"))
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } throws RuntimeException("WM error")

        val request = queue.requestSync()

        assertTrue(request is SyncRequest.Rejected)
        assertEquals(SyncQueueStatus(pending = 1, failed = 0), snapshot())
        assertNull(dao.findByClientId("E1")!!.lastError)
    }
}
