package com.aibill.android.data.ownership

import androidx.room.Room
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.local.entity.CategoryEntity
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.local.work.WorkManagerProvider
import com.aibill.android.data.queue.SyncQueueImpl
import com.aibill.android.data.queue.SyncScheduler
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.model.SyncQueueStatus
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalOwnership
import com.aibill.android.service.SyncWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
 * 本地数据归属 module（[com.aibill.android.domain.ownership.LocalDataOwnership]）的实现测试。
 *
 * ## 这个 module 的价值全在「哪几张表」上，所以断言必须落在**真实 Room**上
 *
 * 收口前，4 张表的清单在两个 module 里各写一遍，靠 KDoc 互相声明「改一处必须改另一处」，
 * 两边各钉一套「清了 4 张表」的测试——那套测试钉的是**各自的调用**，于是「清单本身」
 * 没人钉：往清单里加第 5 张表、或某处漏了一张，两套测试照样全绿。
 *
 * 现在清单只有一份，本类按**表名**直接数行（`SELECT COUNT(*) FROM <表名>`），
 * 三种意图各钉一遍：换了表清单、漏了一张、把「保留」写成「清空」，这里立刻红。
 * 用裸 SQL 而不是 DAO 的 `observe*`，是因为 observe 查询的 WHERE 条件各不相同
 * （`observeAllWithConfirmedSince` 就不返回 `ignored` / `synced` 行），
 * 那会让「表其实没清空」被误判成成立。
 *
 * 归属断言（谁是同号）与意图选择（`AuthRepositoryImplTest`）分属两层：
 * 本类守「意图兑现成什么」，`AuthRepositoryImplTest` 的 `DATA LOSS regression`
 * 与 `logout then SAME/DIFFERENT account login` 守「意图有没有选对」。
 *
 * ## 真实的是什么，mock 的又是什么
 *
 * - **真实**：Room（4 张表 + 队列）、`SyncQueueImpl`（因此 401 判据配对端到端成立）；
 * - **mock**：`SyncScheduler`（Robolectric 里没有可用的 WorkManager，本工程也不引
 *   `work-testing`）、`TokenManager`（真实实现要 `EncryptedSharedPreferences` →
 *   `AndroidKeyStore`，Robolectric 沙箱里抛 `KeyStoreException`；本类只断言
 *   「判据取自哪个方法」，`TokenManager` 自身的语义另有测试）、`SyncLock` /
 *   `WorkManagerProvider`（只需控制握手时机）。
 *
 * 不新增任何依赖：Robolectric + room-testing 已在 `testImplementation`。
 * `application = Application::class` 必需：默认会按 manifest 启动真实 `AiBillApp`，
 * 其 Hilt 图会构造 `TokenManager` → `AndroidKeyStore`，在沙箱里抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LocalDataOwnershipImplTest {

    private lateinit var db: AppDatabase
    private lateinit var pendingDao: PendingTransactionDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var accountDao: AccountDao
    private lateinit var notificationDao: NotificationRecordDao
    private lateinit var syncQueue: SyncQueueImpl
    private lateinit var ownership: LocalDataOwnershipImpl

    private val tokenManager: TokenManager = mockk(relaxed = true)
    private val syncLock: SyncLock = mockk(relaxed = true)
    private val workManagerProvider: WorkManagerProvider = mockk(relaxed = true)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        pendingDao = db.pendingTransactionDao()
        categoryDao = db.categoryDao()
        accountDao = db.accountDao()
        notificationDao = db.notificationRecordDao()
        syncQueue = SyncQueueImpl(pendingDao, RuntimeEnvironment.getApplication())

        ownership = LocalDataOwnershipImpl(
            tokenManager = tokenManager,
            // 真实实现：本类因此能证明「清队列」与「保留队列」是同一个口径
            syncQueue = syncQueue,
            categoryDao = categoryDao,
            accountDao = accountDao,
            notificationRecordDao = notificationDao,
            syncLock = syncLock,
            workManagerProvider = workManagerProvider,
        )

        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(SyncScheduler)
        db.close()
    }

    // ==================== 归属：什么数据归谁 ====================

    /**
     * 判据必须取自**跨 session 存活**的 `last_known_user_id`，不能取 session 字段。
     *
     * 取错的后果是数据丢失：401 时 `endSession()` 会清掉 `user_id`，
     * 于是「Token 过期 → 重登」会判成换号 → 清空离线队列。
     */
    @Test
    fun `ownership is read from the cross-session account id and never from the session one`() {
        every { tokenManager.getLastKnownUserId() } returns 42

        val current = ownership.currentOwnership()

        assertEquals(LocalOwnership(accountId = 42), current)
        assertEquals(true, current.isSameAccount(42))
        assertEquals(false, current.isSameAccount(7))
        verify(exactly = 0) { tokenManager.getUserId() }
    }

    /** 判据不可知（首次安装 / 老版本无此字段）时一律判否 → 保守走换号清空。 */
    @Test
    fun `an unknown account never matches any incoming account`() {
        every { tokenManager.getLastKnownUserId() } returns null

        val current = ownership.currentOwnership()

        assertEquals(null, current.accountId)
        assertEquals(false, current.isSameAccount(42))
        assertEquals(false, current.isSameAccount(0))
    }

    /**
     * `last_known_user_id` 是归属判据，**任何处置都不许写它**。
     *
     * 它只由 `TokenManager.saveSession()` 写、`endSession()` 保留；本 module 只读。
     * 处置顺手改它 = 「同号重登退化成换号」的直接成因。
     */
    @Test
    fun `disposal never rewrites the ownership key`() = runTest {
        every { tokenManager.getLastKnownUserId() } returns 42
        seedAllFourTables()

        ownership.dispose(DisposalIntent.SameAccountRelogin)
        ownership.dispose(DisposalIntent.SwitchingAccount)
        ownership.dispose(DisposalIntent.SwitchingServer)

        verify(exactly = 0) { tokenManager.saveSession(any(), any(), any(), any()) }
        verify(exactly = 0) { tokenManager.endSession(any()) }
        assertEquals(42, ownership.currentOwnership().accountId)
    }

    // ==================== 同号重登：一张表都不能少 ====================

    /**
     * **数据丢失回归**（ARCHITECTURE §6.4 历史 bug 1）：同号重登不得清任何一张表。
     *
     * Token 30 天过期（无 refresh token）恰恰是离线数据最容易堆积的时刻；早期实现在登录
     * 成功时无条件清空，用户「断网时记的、还没同步上去的账」被物理删除且不可恢复。
     * `AuthRepositoryImplTest` 的同名用例守「意图有没有选对」，本用例守「意图兑现得对不对」。
     */
    @Test
    fun `DATA LOSS regression - same account relogin keeps all four tables`() = runTest {
        seedAllFourTables()
        assertRows(1, 1, 1, 2)

        val outcome = ownership.dispose(DisposalIntent.SameAccountRelogin)

        assertEquals(DisposalOutcome.Retained(revivedRows = 0), outcome)
        assertRows(1, 1, 1, 2)
    }

    /**
     * 同号重登的**两件事**都得做：复活 401 卡住的行 + 主动排同步。
     *
     * 只复活不排 → 要等下一次网络跳变；只排不复活 → `getAllPending()` 只捞 `pending`，
     * 那批行永远卡在 failed。判据配对（`ERROR_TOKEN_EXPIRED` ↔ `ERROR_TOKEN_EXPIRED_PATTERN`）
     * 在这里真的穿过 Room，不需要 mock DAO 就能证伪。
     */
    @Test
    fun `same account relogin revives only token-expired rows then reschedules the queue`() = runTest {
        seedAllFourTables()
        pendingDao.insert(pending("P2"))
        pendingDao.markFailed("P1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)
        pendingDao.markFailed("P2", SyncWorker.ERROR_BUSINESS, updatedAt = 100L)
        assertEquals(SyncQueueStatus(pending = 0, failed = 2), queueStatus())

        val outcome = ownership.dispose(DisposalIntent.SameAccountRelogin)

        assertEquals(DisposalOutcome.Retained(revivedRows = 1), outcome)
        // P1 回到 pending 分区（而不只是「不再 failed」）；P2 是不可恢复失败，原样留着
        assertEquals(SyncQueueStatus(pending = 1, failed = 1), queueStatus())
        assertEquals(listOf("P1"), pendingDao.getAllPending().map { it.clientId })
        // 重置后必须主动排同步，否则要等下一次网络回调
        verify(exactly = 1) { SyncScheduler.scheduleSyncIfNeeded(any()) }
        // 其余三张表一张未动
        assertEquals(1, categoryDao.observeAll().first().size)
        assertEquals(1, accountDao.observeAll().first().size)
    }

    /** 没有 401 卡住的行时同样要排同步（`revivedRows == 0` 不是「什么都不做」）。 */
    @Test
    fun `same account relogin reschedules even when nothing was stuck`() = runTest {
        seedAllFourTables()

        val outcome = ownership.dispose(DisposalIntent.SameAccountRelogin)

        assertEquals(DisposalOutcome.Retained(revivedRows = 0), outcome)
        verify(exactly = 1) { SyncScheduler.scheduleSyncIfNeeded(any()) }
    }

    // ==================== 换号 / 换服务器：同一份 4 表清单 ====================

    /** 换号（含首次登录 / userId 未知）：4 张表全清，防 user A 的脏数据进 user B。 */
    @Test
    fun `switching account empties all four owned tables`() = runTest {
        seedAllFourTables()

        val outcome = ownership.dispose(DisposalIntent.SwitchingAccount)

        assertEquals(DisposalOutcome.Cleared, outcome)
        assertRows(0, 0, 0, 0)
    }

    /**
     * 换服务器清的是**同一份**清单（含 `notification_records`）。
     *
     * G-3 之前换服务器只清 3 张，A 服务器的通知审计记录会残留到 B 服务器下——
     * 把第 4 张去掉本用例立刻红。
     */
    @Test
    fun `switching server empties the very same four tables`() = runTest {
        seedAllFourTables()

        val outcome = ownership.dispose(DisposalIntent.SwitchingServer)

        assertEquals(DisposalOutcome.Cleared, outcome)
        assertRows(0, 0, 0, 0)
    }

    /** 清空**不得**顺手复活 401 行或排同步：那些是同号重登才做的事。 */
    @Test
    fun `clearing never revives failed rows nor reschedules`() = runTest {
        seedAllFourTables()
        pendingDao.markFailed("P1", SyncWorker.ERROR_TOKEN_EXPIRED, updatedAt = 100L)

        ownership.dispose(DisposalIntent.SwitchingServer)

        assertEquals(SyncQueueStatus(pending = 0, failed = 0), queueStatus())
        verify(exactly = 0) { SyncScheduler.scheduleSyncIfNeeded(any()) }
    }

    /**
     * 换服务器**不做** SyncLock 握手（与收口前逐字一致，刻意不改）。
     *
     * `ServerConfig` 只在未登录态可达（`MainViewModel` 的 `startRoute` 与 `LoginScreen` 的入口），
     * 此时没有有效会话、也就没有在飞的同步循环要取消。钉住它，是为了让「顺手补上握手」
     * 变成一次显眼的决定，而不是无声的改动。
     */
    @Test
    fun `switching server does not touch the sync lock`() = runTest {
        seedAllFourTables()

        ownership.dispose(DisposalIntent.SwitchingServer)

        verify(exactly = 0) { workManagerProvider.cancelSyncWorker() }
        verify(exactly = 0) { syncLock.isActive() }
    }

    // ==================== SyncLock 握手 ====================

    /** 握手顺序：先 cancel 在飞的 worker，再等锁释放。 */
    @Test
    fun `awaitSyncQuiescence cancels the in-flight worker and waits for the lock to release`() = runTest {
        every { syncLock.isActive() } returnsMany listOf(true, true, false)

        ownership.awaitSyncQuiescence()

        verify(exactly = 1) { workManagerProvider.cancelSyncWorker() }
        verify(exactly = 3) { syncLock.isActive() }
    }

    /**
     * 锁一直不放时也不能把登录卡死——等锁有 3s 上限，超时照常返回（最坏只丢一次同步循环）。
     *
     * ⚠️ 只断言「一定会退出」，不断言耗时：等锁跨了虚拟时钟与真实墙钟，
     * 逐次断言毫秒数只会把测试变成竞速。
     */
    @Test
    fun `awaitSyncQuiescence gives up instead of blocking forever`() = runTest {
        every { syncLock.isActive() } returns true

        ownership.awaitSyncQuiescence()

        verify(exactly = 1) { workManagerProvider.cancelSyncWorker() }
        verify(atLeast = 1) { syncLock.isActive() }
    }

    // ==================== 夹具 ====================

    private suspend fun queueStatus(): SyncQueueStatus = syncQueue.observeStatus().first()

    private fun pending(clientId: String) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        source = "app_notification",
        clientCreatedAt = "2026-07-05T04:00:00Z",
    )

    /** 四张表各塞一行（通知表塞两行，便于「只清一半」的错删被看出来）。 */
    private suspend fun seedAllFourTables() {
        pendingDao.insert(pending("P1"))
        categoryDao.insertAll(
            listOf(CategoryEntity(id = 1, name = "餐饮", type = "expense", icon = "🍜")),
        )
        accountDao.insertAll(
            listOf(AccountEntity(id = 1, name = "现金", type = "cash", icon = "💵")),
        )
        notificationDao.insert(
            NotificationRecordEntity(
                packageName = "com.tencent.mm",
                content = "支付成功 ¥32.00",
                parsedAmount = 3200,
                status = "confirmed",
            ),
        )
        notificationDao.insert(
            NotificationRecordEntity(
                packageName = "com.eg.android.AlipayGphone",
                content = "支付成功 ¥12.00",
                parsedAmount = 1200,
                status = "raw",
            ),
        )
    }

    /**
     * 按**表名**数行；顺序固定为「本地用户数据」的清单顺序。
     */
    private fun rowCounts(): List<Int> = OWNED_TABLES.map { table ->
        db.query("SELECT COUNT(*) FROM $table", arrayOf<Any?>()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    /** 失败信息里带上表名与顺序，否则「第 2 张表没清空」这种问题很难一眼看出来。 */
    private fun assertRows(vararg expected: Int) {
        assertEquals("按表名数行：$OWNED_TABLES", expected.toList(), rowCounts())
    }

    private companion object {
        /** 与生产清单同序（见 `LocalDataOwnershipImpl` 的 KDoc 表格）。 */
        private val OWNED_TABLES = listOf(
            "pending_transactions",
            "categories",
            "accounts",
            "notification_records",
        )
    }
}
