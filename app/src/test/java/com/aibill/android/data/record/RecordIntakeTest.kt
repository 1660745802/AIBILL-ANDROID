package com.aibill.android.data.record

import android.content.Context
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.queue.RecordWrite
import com.aibill.android.data.queue.RecordWriter
import com.aibill.android.data.queue.SyncQueueImpl
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.record.CapturedNotification
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.ReceiptPolicy
import com.aibill.android.domain.record.SyncScheduling
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.domain.usecase.StreakInfo
import com.aibill.android.domain.usecase.StreakTracker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.data.queue.SyncScheduler
import com.aibill.android.service.WidgetDataUpdater
import com.aibill.android.util.NotificationHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 入账 module（[RecordIntake]）的行为测试。
 *
 * ## 为什么 interface 就是测试面
 *
 * 收口前，6 条「记一笔」写入路径各自手写、各自无测试；任何一条不变量的改动要改 6 处，
 * 于是漏过一次——4 条路径用 `SimpleDateFormat(Locale.getDefault())` 落 date/time，
 * 泰语 locale 下写进库的是佛历年 `2569-…`、阿拉伯语 locale 下是阿拉伯数字，
 * 而服务端契约是 `YYYY-MM-DD`。收口后这 6 条路径共用同一个入口，
 * **入口就是全部写入行为的测试面**：字段装配、调用顺序、locale 不变都在这里钉。
 *
 * ## 本类**不用** [com.aibill.android.data.queue.DirectRecordWriter]
 *
 * `DirectRecordWriter` 的三步写入（建审计行 → 写队列行 → 回填 `status` + `linked_client_id`）
 * 由 `DirectRecordWriterTest` **直接驱动**并逐行断言（含两表关联键）。
 * 本类注入的是匿名 `object : RecordWriter`：它只关心入账 module 自己的顺序与字段，
 * 用 fake 记录调用序列（`write` / `confirm-record` / `schedule` / `widget` / `receipt`）
 * 才能断言到「AUTO 把 streak 插在排调度与刷 widget 之间」这种次序不变量。
 *
 * ⚠️ 因此「第三步真的回填了审计记录」在本类**看不到**——若有人删掉
 * `RecordWriter.applyRecordWrite` 的第三步，本类**仍然全绿**。
 * 这正是收口当时留下的覆盖缺口（类 KDoc 写着「为什么用 DirectRecordWriter」，
 * 代码却注入的是匿名 object），已在架构候选 3 补上。
 *
 * ⚠️ 本类同样**不覆盖**事务回滚（那要 Robolectric/Room 集成测试）；
 * 「三步写在一个事务里」这条由生产 [com.aibill.android.data.queue.RoomRecordWriter]
 * 的实现保证，不由本类保证。
 *
 * ## 时钟是注入的
 *
 * 时刻固定为 2026-07-10T12:30:00Z，于是「落库字段对不对」可以被全等断言，
 * 而不是只能断言到「是今天」。locale 不变测试也靠它把「佛历年」这个坑钉死。
 */
@DisplayName("RecordIntake：入账 module 的字段、顺序与 locale 不变")
class RecordIntakeTest {

    private val context: Context = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk()
    private val learningEngine: CategoryLearningEngine = mockk(relaxed = true)
    private val streakTracker: StreakTracker = mockk(relaxed = true)
    private val rulesManager: NotificationRulesManager = mockk(relaxed = true)

    /** 跨表写入与排调度的调用顺序；每个 hook 都往里记一笔 */
    private val calls = mutableListOf<String>()
    private val insertedRows = mutableListOf<PendingTransactionEntity>()
    private val insertedRecords = mutableListOf<NotificationRecordEntity>()

    private val fixedZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant: Instant = Instant.parse("2026-07-10T04:30:00Z") // = 12:30 +08:00

    private lateinit var intake: RecordIntake

    @BeforeEach
    fun setUp() {
        calls.clear()
        insertedRows.clear()
        insertedRecords.clear()

        mockkObject(SyncScheduler)
        mockkObject(WidgetDataUpdater)
        mockkObject(NotificationHelper)

        every { SyncScheduler.scheduleSyncIfNeeded(any()) } answers { calls += "schedule"; Unit }
        every { WidgetDataUpdater.notifyTransactionAdded(any(), any(), any(), any()) } answers { calls += "widget"; Unit }
        every {
            NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers { calls += "receipt"; Unit }
        every { userPreferences.notificationPrivacy } returns flowOf(false)

        val writer = object : RecordWriter {
            override suspend fun write(write: RecordWrite): Long? {
                calls += "write"
                val newId = write.newRecord?.also { insertedRecords += it }?.let { 7L }
                insertedRows += write.pending
                // 回填既有审计记录这一步由 DirectRecordWriter 做，这里只记顺序
                // （第三步本身由 DirectRecordWriterTest 断言）
                if (write.confirmRecordId != null || newId != null) calls += "confirm-record"
                return newId
            }
        }
        intake = buildIntake(writer)
    }

    /** 真实实现 + 换掉写入器（其余依赖仍来自 setUp 的 stub）。 */
    private fun buildIntake(writer: RecordWriter): RecordIntake {
        val clock = object : RecordClock {
            override fun now(): RecordMoment = RecordMoment.of(fixedInstant, fixedZone)
        }

        return RecordIntakeImpl(
            context = context,
            recordWriter = writer,
            syncQueue = SyncQueueImpl(mockk(relaxed = true), context),
            recordClock = clock,
            userPreferences = userPreferences,
            categoryLearningEngine = learningEngine,
            streakTracker = streakTracker,
            rulesManager = rulesManager,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SyncScheduler)
        unmockkObject(WidgetDataUpdater)
        unmockkObject(NotificationHelper)
    }

    private fun capturedNotification() = CapturedNotification(
        packageName = "com.tencent.mm",
        title = "微信支付",
        content = "支付成功 ¥32.00",
        receivedAt = 1_753_000_000_000L,
    )

    private fun onlyRow(): PendingTransactionEntity {
        assertEquals(1, insertedRows.size, "应当只写一行队列")
        return insertedRows.single()
    }

    // ═══════════════════════════════════════════════════════════════
    // 顺序不变量：先写库、后排调度
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AI 自动入账：写库 → 排调度 → 刷 widget → 回执，学习在回执之后")
    fun `notification auto keeps write-then-schedule order`() = runTest {
        coEvery { streakTracker.onTransactionRecorded() } returns StreakInfo()

        intake.intake(
            RecordIntent.NotificationAuto(
                captured = capturedNotification(),
                amount = 3200,
                type = "expense",
                categoryId = 7,
                categoryName = "餐饮",
                categoryIcon = "🍜",
                description = "午餐",
            )
        )

        assertEquals(listOf("write", "confirm-record", "schedule", "widget", "receipt"), calls)
        // streak 只在这一条路径刷，且在排调度之后、刷 widget 之前
        coVerify(exactly = 1) { streakTracker.onTransactionRecorded() }
    }

    @Test
    @DisplayName("弹窗确认 / 通知中心单条确认：同样是先写库后排调度")
    fun `confirm intents keep write-then-schedule order`() = runTest {
        intake.intake(
            RecordIntent.NotificationConfirm(
                recordId = 42L,
                packageName = "com.tencent.mm",
                amount = 100,
                type = "expense",
                description = "午饭",
                learn = false,
            )
        )
        assertEquals(listOf("write", "confirm-record", "schedule", "widget", "receipt"), calls)

        calls.clear()
        intake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = 43L,
                packageName = "com.tencent.mm",
                amount = 100,
                type = "expense",
                description = "午饭",
            )
        )
        assertEquals(listOf("write", "confirm-record", "schedule", "widget"), calls)
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 1：AI 自动入账（NotificationProcessor.directInsert）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AI 自动入账：写全字段 + 新建审计记录 + 回填 clientId")
    fun `notification auto writes the full row`() = runTest {
        val outcome = intake.intake(
            RecordIntent.NotificationAuto(
                captured = capturedNotification(),
                amount = 3200,
                type = "expense",
                categoryId = 7,
                categoryName = "餐饮",
                categoryIcon = "🍜",
                description = "午餐",
            )
        )

        val row = onlyRow()
        assertEquals(outcome.clientId, row.clientId)
        // 新建审计行的 id 要随结果回传（它就是回执的通知 id / 撤销的定位键）
        assertEquals(7L, outcome.recordId)
        assertTrue(outcome.syncScheduled)
        assertTrue(row.clientId.isNotBlank(), "clientId 是幂等与撤销的唯一凭据")
        assertEquals(3200, row.amount)
        assertEquals("expense", row.type)
        assertEquals(7, row.categoryId)
        assertEquals("餐饮", row.categoryName)
        assertEquals("🍜", row.categoryIcon)
        assertEquals("午餐", row.description)
        assertEquals("app_notification", row.source)
        assertEquals("微信支付", row.sourceDetail)
        assertEquals("pending", row.syncStatus)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        assertEquals(fixedInstant.toString(), row.clientCreatedAt)
        assertNull(row.aiRawInput)

        // 新建审计记录（status 直接是 confirmed），随后回填 linked_client_id
        assertEquals(1, insertedRecords.size)
        val record = insertedRecords.single()
        assertEquals("com.tencent.mm", record.packageName)
        assertEquals("微信支付", record.title)
        assertEquals("支付成功 ¥32.00", record.content)
        assertEquals(3200, record.parsedAmount)
        assertEquals("confirmed", record.status)
    }

    @Test
    @DisplayName("AI 自动入账：只这条路径刷 streak / 读隐私档 / 学分类")
    fun `notification auto is the only path with streak privacy and learning`() = runTest {
        coEvery { learningEngine.matchCategory(any()) } returns 3
        coEvery { streakTracker.onTransactionRecorded() } returns StreakInfo()

        intake.intake(
            RecordIntent.NotificationAuto(
                captured = capturedNotification(),
                amount = 3200,
                type = "expense",
                categoryId = 7,
                description = "午餐",
            )
        )

        // 不预测分类：学习命中在 NotificationProcessor.process() 里就算好了，
        // 且它还参与「信息是否完整」的判定，挪进 module 会改变待审/入库判定
        coVerify(exactly = 0) { learningEngine.matchCategory(any()) }
        coVerify(exactly = 1) { learningEngine.learnFromCorrection("午餐", 7) }
        coVerify(exactly = 1) { streakTracker.onTransactionRecorded() }
    }

    @Test
    @DisplayName("回执策略由意图决定：auto / confirm-receipt / none")
    fun `receipt policy is decided by the intent`() {
        assertEquals(
            ReceiptPolicy.AUTO,
            RecordIntent.NotificationAuto(capturedNotification(), 1, "expense", null, description = null).receipt,
        )
        assertEquals(
            ReceiptPolicy.CONFIRM_RECEIPT,
            RecordIntent.NotificationConfirm(1L, "p", 1, "expense", null, learn = false).receipt,
        )
        assertEquals(
            ReceiptPolicy.NONE,
            RecordIntent.NotificationCenterConfirm(1L, "p", 1, "expense", null).receipt,
        )
        assertEquals(
            ReceiptPolicy.NONE,
            RecordIntent.ExternalQuickRecord(1, "expense", null).receipt,
        )
        assertEquals(
            ReceiptPolicy.NONE,
            RecordIntent.ManualOffline(Transaction(clientId = "c", type = TransactionType.EXPENSE, amount = 1, date = "2026-07-05")).receipt,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 2：弹窗确认 / 弹窗带备注确认（NotificationActionReceiver）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("弹窗确认：预测分类但不学习、隐私写死 false、不写分类 name/icon")
    fun `notification confirm predicts but does not learn`() = runTest {
        coEvery { learningEngine.matchCategory("午饭") } returns 5

        intake.intake(
            RecordIntent.NotificationConfirm(
                recordId = 42L,
                packageName = "com.tencent.mm",
                amount = 2500,
                type = "expense",
                description = "午饭",
                learn = false,
            )
        )

        coVerify(exactly = 1) { learningEngine.matchCategory("午饭") }
        // handleConfirm 的历史行为就是不学习，与带备注确认的差异保留
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }

        val row = onlyRow()
        assertEquals(5, row.categoryId)
        // 只写分类 id，不写 name/icon（与「手工落离线」写冗余 name/icon 的差异保留）
        assertNull(row.categoryName)
        assertNull(row.categoryIcon)
        // 弹窗确认读**硬编码**来源名映射（不带云控），与 AI 自动路径的差异保留
        assertEquals("微信支付", row.sourceDetail)
        assertEquals("app_notification", row.source)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
    }

    @Test
    @DisplayName("弹窗带备注确认：同样预测分类，并额外学习")
    fun `notification confirm with note learns`() = runTest {
        coEvery { learningEngine.matchCategory("  公司食堂  ") } returns 5

        intake.intake(
            RecordIntent.NotificationConfirm(
                recordId = 42L,
                packageName = "com.tencent.mm",
                amount = 2500,
                type = "expense",
                description = "  公司食堂  ",
                learn = true,
            )
        )

        // 学习用的是 trim 后的描述（与原 handleConfirmWithNote 一致）
        coVerify(exactly = 1) { learningEngine.learnFromCorrection("公司食堂", 5) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 3：通知中心单条 / 批量确认（NotificationCenterViewModel）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("通知中心确认：不预测、不学习、不发回执，标签逗号拼接")
    fun `notification center confirm never predicts nor learns`() = runTest {
        intake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = 9L,
                packageName = "com.tencent.mm",
                amount = 8800,
                type = "expense",
                description = "晚餐",
                categoryId = 3,
                categoryName = "交通",
                categoryIcon = "🚗",
                tags = listOf("出差", "报销"),
            )
        )

        coVerify(exactly = 0) { learningEngine.matchCategory(any()) }
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }
        assertFalse(calls.contains("receipt"), "通知中心确认不另发回执：用户已经在界面上看到结果")

        val row = onlyRow()
        assertEquals(3, row.categoryId)
        assertEquals("交通", row.categoryName)
        assertEquals("🚗", row.categoryIcon)
        assertEquals("出差,报销", row.tags)
        assertEquals("pending", row.syncStatus)
    }

    @Test
    @DisplayName("批量确认：每笔不排调度，结果里如实报告「没排」，由批量调用方收尾排一次")
    fun `batch confirm defers a single sync`() = runTest {
        repeat(3) { i ->
            val outcome = intake.intake(
                RecordIntent.NotificationCenterConfirm(
                    recordId = i.toLong(),
                    packageName = "com.tencent.mm",
                    amount = 100 * (i + 1),
                    type = "expense",
                    description = "第 $i 笔",
                    scheduling = SyncScheduling.DEFERRED,
                )
            )
            // A3：入账入口自己回答「本笔排了没排」，调用方不再需要记住调一个收尾方法
            assertFalse(outcome.syncScheduled, "DEFERRED 的本笔必须如实报告未排调度")
        }

        // 逐笔只写库 + 刷 widget；把 1 次入队变成 N 次就是行为变更
        assertFalse(calls.contains("schedule"), "DEFERRED 时每笔都不排调度")
        assertEquals(3, insertedRows.size)
        assertEquals(3, calls.count { it == "widget" })
    }

    @Test
    @DisplayName("单条确认与其它意图：结果里如实报告「已排调度」")
    fun `immediate intents report that sync was scheduled`() = runTest {
        val single = intake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = 1L,
                packageName = "com.tencent.mm",
                amount = 100,
                type = "expense",
                description = "单条",
            )
        )
        val offline = intake.intake(
            RecordIntent.ManualOffline(
                Transaction(
                    clientId = "C-1",
                    type = TransactionType.EXPENSE,
                    amount = 100,
                    date = "2026-07-05",
                )
            )
        )

        assertTrue(single.syncScheduled)
        assertTrue(offline.syncScheduled)
        // 通知中心确认不改既有审计行 → 没有新建的审计 id 可回传
        assertNull(single.recordId)
    }

    @Test
    @DisplayName("A2：写入器没回传审计记录 id 时，宁可不发回执也不拿假 id 去盖真实通知")
    fun `auto intake never fabricates a record id for the receipt`() = runTest {
        // 历史实现是 `.orZero()`：writer 漏返回 id → recordId=0 → 用 id=0 发一条通知，
        // 把别的通知覆盖掉，用户点「撤销」会撤销错账。
        val silentWriter = object : RecordWriter {
            override suspend fun write(write: RecordWrite): Long? {
                calls += "write"
                insertedRows += write.pending
                return null
            }
        }

        val outcome = buildIntake(silentWriter).intake(
            RecordIntent.NotificationAuto(
                captured = capturedNotification(),
                amount = 3200,
                type = "expense",
                categoryId = 7,
                description = "午餐",
            )
        )

        assertNull(outcome.recordId, "拿不到 id 就如实返回 null")
        assertFalse(calls.contains("receipt"), "没有 id 就不发回执：宁可没有回执，也不能盖掉真实通知")
        // 其余步骤照旧：账仍然落库、照排调度、照刷 widget、照学习
        assertEquals(1, insertedRows.size)
        assertEquals(listOf("write", "schedule", "widget"), calls)
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 4：外部 Intent 静默入账（ExternalActionReceiver）
    //
    // 这两条断言原先住在 `ExternalActionReceiverTest`，直接调接收器上的
    // `internal suspend fun handleAutoConfirmRecord`——那是为绕开 Hilt 门禁
    // （`Hilt_ExternalActionReceiver.onReceive` / `goAsync()` 都会 `inject()`）
    // 而留的后门。候选 5 撤掉后门：写入逻辑本来就在本 module 里，用例随之搬到这里。
    // 接收器里剩下的「权限被拒 / autoConfirm=false 不写库」两条分支**仍不可测**
    // （它们需要 `@HiltAndroidApp` 的 Application），见 ARCHITECTURE §11.3。
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("静默入账：来源标记 tasker_quick_record、无时区 client_created_at、不刷 widget")
    fun `external quick record keeps its own payload`() = runTest {
        val outcome = intake.intake(
            RecordIntent.ExternalQuickRecord(amount = 2590, type = "expense", description = "咖啡")
        )

        assertEquals(listOf("write", "schedule"), calls)
        // 不刷 widget（其余通知路径都刷）——原实现就没有刷，保留
        assertFalse(calls.contains("widget"))
        // 不翻审计表
        assertTrue(insertedRecords.isEmpty())

        val row = onlyRow()
        // 意图载荷原样落库：外部 Intent 的金额/类型/描述不能被同步逻辑改动
        assertEquals(2590, row.amount)
        assertEquals("expense", row.type)
        assertEquals("咖啡", row.description)
        assertEquals(outcome.clientId, row.clientId)
        // clientId 是撤销与幂等的唯一凭据（AutoRecordActionReceiver 按它查行），
        // 空值会让这笔账永远无法被撤销。
        assertTrue(row.clientId.isNotBlank())
        assertEquals("external_intent", row.source)
        // 来源标记的**具体值**：tasker_quick_record 是 AutoRecord 与本入口的区分依据，
        // 丢了它两类记录在服务端就分不出来。
        assertEquals("tasker_quick_record", row.sourceDetail)
        assertEquals("pending", row.syncStatus)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        // 历史口径：本入口的 client_created_at 是**无时区**的本地日期时间串
        assertEquals("2026-07-10T12:30", row.clientCreatedAt)
    }

    @Test
    @DisplayName("静默入账：排调度抛异常也不抛、不回滚，账仍恰好写一行")
    fun `external quick record never rolls back when scheduling throws`() = runTest {
        // 与「落离线」那条同源：证明「写库之后才排调度」，且排调度失败不影响已写入的这一行。
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } answers {
            calls += "schedule"
            throw RuntimeException("WM down")
        }

        // 不抛即通过：静默入账跑在 ApplicationScope 上，异常逃出去 = 崩进程
        // （本工程没有全局 CoroutineExceptionHandler）。
        intake.intake(
            RecordIntent.ExternalQuickRecord(amount = 100, type = "expense", description = "午饭")
        )

        assertEquals(1, insertedRows.size)
        assertEquals(listOf("write", "schedule"), calls)
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 5：手工记账落离线（TransactionRepositoryImpl.createTransactionOffline）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("落离线：沿用调用方的 clientId 与用户所选日期，冗余写分类/账户字段")
    fun `manual offline keeps caller client id and user picked date`() = runTest {
        val transaction = Transaction(
            clientId = "C-offline-1",
            type = TransactionType.EXPENSE,
            amount = 3200,
            categoryId = 2,
            categoryName = "餐饮",
            categoryIcon = "🍚",
            accountId = 9,
            accountName = "招行",
            description = "午饭",
            date = "2026-07-05",
            time = "12:30",
            tags = listOf("工作日", "报销"),
            source = TransactionSource.MANUAL,
        )

        intake.intake(RecordIntent.ManualOffline(transaction))

        val row = onlyRow()
        assertEquals("C-offline-1", row.clientId)
        assertEquals(transaction.categoryId, row.categoryId)
        assertEquals("餐饮", row.categoryName)
        assertEquals("🍚", row.categoryIcon)
        assertEquals(9, row.accountId)
        assertEquals("招行", row.accountName)
        assertEquals("工作日,报销", row.tags)
        assertEquals("manual", row.source)
        // 手工记账用**用户当时选的时间**，不是入账时刻
        assertEquals("2026-07-05", row.date)
        assertEquals("12:30", row.time)
        assertEquals("pending", row.syncStatus)
        // 不刷 widget（其余通知路径都刷）——原实现就没有刷，保留
        assertEquals(listOf("write", "schedule"), calls)
    }

    @Test
    @DisplayName("落离线：排调度抛异常也不抛、不回滚，账仍恰好写一行")
    fun `manual offline never rolls back when scheduling throws`() = runTest {
        // 记录顺序后再抛：证明「写库之后才排调度」，且排调度失败不影响已写入的这一行
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } answers {
            calls += "schedule"
            throw RuntimeException("WM down")
        }

        // 不抛即通过（异常若逃出 viewModelScope 会崩进程，且会让 isSaving 复位被跳过）
        intake.intake(
            RecordIntent.ManualOffline(
                Transaction(
                    clientId = "C-1",
                    type = TransactionType.EXPENSE,
                    amount = 100,
                    date = "2026-07-05",
                )
            )
        )

        assertEquals(1, insertedRows.size)
        assertEquals(listOf("write", "schedule"), calls)
    }

    // ═══════════════════════════════════════════════════════════════
    // locale 不变：这是本切片存在的核心理由
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("泰语 locale 下落库日期仍是 ISO 年份，不是佛历年 2569")
    fun `thai locale still yields ISO gregorian year`() = runTest {
        withDefaultLocale(Locale("th", "TH")) {
            intake.intake(
                RecordIntent.NotificationConfirm(
                    recordId = 1L,
                    packageName = "com.tencent.mm",
                    amount = 100,
                    type = "expense",
                    description = "午饭",
                    learn = false,
                )
            )
        }

        val date = onlyRow().date
        // 佛历年日历下 SimpleDateFormat("yyyy-MM-dd", th-TH) 会写进 "2569-07-10"
        assertFalse(date.startsWith("2569"), "落库日期不能是佛历年：$date")
        assertEquals("2026-07-10", date)
        // 必须能被 ISO 解析器读回来（服务端按 YYYY-MM-DD 解析）
        assertEquals(2026, java.time.LocalDate.parse(date).year)
    }

    @Test
    @DisplayName("阿拉伯语 locale 下落库日期/时间仍是 ASCII 数字")
    fun `arabic locale still yields ascii digits`() = runTest {
        withDefaultLocale(Locale("ar", "EG")) {
            intake.intake(
                RecordIntent.NotificationCenterConfirm(
                    recordId = 1L,
                    packageName = "com.tencent.mm",
                    amount = 100,
                    type = "expense",
                    description = "午饭",
                )
            )
        }

        val row = onlyRow()
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        // 阿拉伯语 locale 下 SimpleDateFormat 会写出 ٢٠٢٦-٠٧-١٠，必须一个非 ASCII 字符都不剩
        assertTrue(row.date.all { it.code < 128 }, "落库日期必须全是 ASCII：${row.date}")
        assertTrue((row.time ?: "").all { it.code < 128 }, "落库时间必须全是 ASCII：${row.time}")
    }
}

/** 临时切默认 locale，用完必还原——否则会污染同 JVM 内的其它用例。 */
private inline fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    return try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}
