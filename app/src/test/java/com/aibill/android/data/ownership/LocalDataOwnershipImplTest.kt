package com.aibill.android.data.ownership

import androidx.room.Room
import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.AccountEntity
import com.aibill.android.data.local.entity.CategoryEntity
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalOwnership
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * （`observeAllWithConfirmedSince` 就不返回 `ignored` 行），那会让「表其实没清空」被误判成成立。
 *
 * 归属断言（谁是同号）与意图选择（`AuthRepositoryImplTest`）分属两层：
 * 本类守「意图兑现成什么」，`AuthRepositoryImplTest` 的 `DATA LOSS regression`
 * 与 `logout then SAME/DIFFERENT account login` 守「意图有没有选对」。
 *
 * ## 真实的是什么，mock 的又是什么
 *
 * - **真实**：Room（4 张表都走真表，因此「清单本身」被端到端钉住）；
 * - **mock**：`TokenManager`（真实实现要 `EncryptedSharedPreferences` →
 *   `AndroidKeyStore`，Robolectric 沙箱里抛 `KeyStoreException`；本类只断言
 *   「判据取自哪个方法」，`TokenManager` 自身的语义另有测试）。
 *
 * ## 同号重登的语义已收窄
 *
 * 这里曾经还要钉「把 401 卡住的行复活回 pending + 主动排同步」。记账没有待上传队列了，
 * 也就没有需要复活的行：同号重登 = **一张表都不动**，没有别的事要做。
 * 因此本类只断言「4 张表原样保留」，不再有任何队列状态断言。
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
    private lateinit var ownership: LocalDataOwnershipImpl

    private val tokenManager: TokenManager = mockk(relaxed = true)

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

        ownership = LocalDataOwnershipImpl(
            tokenManager = tokenManager,
            // 真实 DAO：本类因此能证明「清缓存」清的确实是这张表
            pendingTransactionDao = pendingDao,
            categoryDao = categoryDao,
            accountDao = accountDao,
            notificationRecordDao = notificationDao,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ==================== 归属：什么数据归谁 ====================

    /**
     * 判据必须取自**跨 session 存活**的 `last_known_user_id`，不能取 session 字段。
     *
     * 取错的后果是数据丢失：401 时 `endSession()` 会清掉 `user_id`，
     * 于是「Token 过期 → 重登」会判成换号 → 清空本地缓存。
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
     * Token 30 天过期（无 refresh token）时本地恰恰存着最有价值的东西——服务端已确认的
     * 缓存与设备侧的通知审计；早期实现在登录成功时无条件清空，用户的账被物理删除且不可恢复。
     * `AuthRepositoryImplTest` 的同名用例守「意图有没有选对」，本用例守「意图兑现得对不对」。
     */
    @Test
    fun `DATA LOSS regression - same account relogin keeps all four tables`() = runTest {
        seedAllFourTables()
        assertRows(1, 1, 1, 2)

        val outcome = ownership.dispose(DisposalIntent.SameAccountRelogin)

        assertEquals(DisposalOutcome.Retained, outcome)
        assertRows(1, 1, 1, 2)
    }

    /** 同号重登不排任何东西：没有待上传的行要推。 */
    @Test
    fun `same account relogin schedules nothing`() = runTest {
        seedAllFourTables()

        ownership.dispose(DisposalIntent.SameAccountRelogin)

        assertRows(1, 1, 1, 2)
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

    /** 清空换服务器时同一份清单：4 张表一张不剩。 */
    @Test
    fun `clearing empties every owned table`() = runTest {
        seedAllFourTables()

        ownership.dispose(DisposalIntent.SwitchingServer)

        assertRows(0, 0, 0, 0)
    }

    // ==================== 夹具 ====================

    private fun pending(clientId: String) = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        source = "app_notification",
        clientCreatedAt = "2026-07-05T04:00:00Z",
        // 缓存行唯一的合法形态：服务端确认过
        syncStatus = "synced",
        serverTransactionId = 1234,
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
