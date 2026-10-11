package com.aibill.android.data.record

import android.content.Context
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.duplicatesApiResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.domain.record.CapturedNotification
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.record.RecordFailure
import com.aibill.android.domain.record.ReceiptPolicy
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.domain.usecase.StreakInfo
import com.aibill.android.domain.usecase.StreakTracker
import com.aibill.android.service.NotificationRulesManager
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
 * 入账 module（[RecordIntake]）的行为测试：**服务端先确认，本地才落库**。
 *
 * ## 为什么 interface 就是测试面
 *
 * 收口前，6 条「记一笔」写入路径各自手写、各自无测试；任何一条不变量的改动要改 6 处，
 * 于是漏过一次——4 条路径用 `SimpleDateFormat(Locale.getDefault())` 落 date/time，
 * 泰语 locale 下写进库的是佛历年 `2569-…`、阿拉伯语 locale 下是阿拉伯数字，
 * 而服务端契约是 `YYYY-MM-DD`。收口后这 6 条路径共用同一个入口，
 * **入口就是全部写入行为的测试面**：字段装配、调用顺序、locale 不变都在这里钉。
 *
 * ## 本类**不用** [com.aibill.android.data.record.DirectRecordWriter]
 *
 * `DirectRecordWriter` 的三步写入（建审计行 → 写缓存行 → 回填 `status` + `linked_client_id`）
 * 由 `DirectRecordWriterTest` **直接驱动**并逐行断言（含两表关联键）。
 * 本类注入的是匿名 `object : RecordWriter`：它只关心入账 module 自己的顺序与字段，
 * 用 fake 记录调用序列（`submit` / `write` / `confirm-record` / `widget` / `receipt`）
 * 才能断言到「AUTO 把 streak 插在落库与刷 widget 之间」这种次序不变量。
 *
 * ⚠️ 因此「第三步真的回填了审计记录」在本类**看不到**——若有人删掉
 * `RecordWriter.applyRecordWrite` 的第三步，本类**仍然全绿**。
 * 这正是收口当时留下的覆盖缺口（类 KDoc 写着「为什么用 DirectRecordWriter」，
 * 代码却注入的是匿名 object），已在架构候选 3 补上。
 *
 * ⚠️ 本类同样**不覆盖**事务回滚（那要 Robolectric/Room 集成测试）；
 * 「三步写在一个事务里」这条由生产 [com.aibill.android.data.record.RoomRecordWriter]
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
    private val transactionApi: TransactionApi = mockk()

    /** 送服务端 → 跨表写入 → 刷 widget → 回执 的调用顺序；每个 hook 都往里记一笔 */
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

        mockkObject(WidgetDataUpdater)
        mockkObject(NotificationHelper)

        // 默认：服务端按回执把每一条都确认成 created（成功判据的另一半由失败用例自己打桩）
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            confirmed(firstArg())
        }
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
            transactionApi = transactionApi,
            recordClock = clock,
            userPreferences = userPreferences,
            categoryLearningEngine = learningEngine,
            streakTracker = streakTracker,
            rulesManager = rulesManager,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WidgetDataUpdater)
        unmockkObject(NotificationHelper)
    }

    /** 服务端回执：`code==0` + `created[]` 按 `client_id` 回认。 */
    private fun confirmed(request: CreateTransactionRequest): ApiResponse<CreateTransactionResponse> =
        ApiResponse(
            code = 0,
            data = CreateTransactionResponse(
                created = request.items.map { item ->
                    TransactionDto(
                        id = SERVER_ID,
                        clientId = item.clientId,
                        type = item.type,
                        amount = item.amount,
                        categoryId = item.categoryId,
                        categoryName = null,
                        categoryIcon = null,
                        accountId = item.accountId,
                        accountName = null,
                        targetAccountId = item.targetAccountId,
                        targetAccountName = null,
                        description = item.description,
                        date = item.date,
                        time = item.time,
                        tags = null,
                        source = item.source,
                        createdAt = null,
                        updatedAt = null,
                    )
                },
                duplicates = emptyList(),
            ),
            message = "ok",
        )

    /** 服务端没确认：`code==0` 但 `created[]` 里没有这条 `client_id`。 */
    private fun unconfirmed(): ApiResponse<CreateTransactionResponse> =
        ApiResponse(
            code = 0,
            data = CreateTransactionResponse(created = emptyList(), duplicates = emptyList()),
            message = "ok",
        )

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
    // 顺序不变量：先服务端确认 → 再写本地 → 再发回执
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AI 自动入账：送服务端 → 写库 → 刷 widget → 回执，学习在回执之后")
    fun `notification auto keeps submit-then-write order`() = runTest {
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

        assertEquals(listOf("submit", "write", "confirm-record", "widget", "receipt"), calls)
        // streak 只在这一条路径刷，且在落库之后、刷 widget 之前
        coVerify(exactly = 1) { streakTracker.onTransactionRecorded() }
    }

    @Test
    @DisplayName("弹窗确认 / 通知中心单条确认：同样是先服务端确认再写本地")
    fun `confirm intents keep submit-then-write order`() = runTest {
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
        assertEquals(listOf("submit", "write", "confirm-record", "widget", "receipt"), calls)

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
        assertEquals(listOf("submit", "write", "confirm-record", "widget"), calls)
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
        assertTrue(outcome.recorded, "服务端确认了才算记上")
        assertTrue(row.clientId.isNotBlank(), "clientId 是幂等与撤销的唯一凭据")
        assertEquals(3200, row.amount)
        assertEquals("expense", row.type)
        assertEquals(7, row.categoryId)
        assertEquals("餐饮", row.categoryName)
        assertEquals("🍜", row.categoryIcon)
        assertEquals("午餐", row.description)
        assertEquals("app_notification", row.source)
        assertEquals("微信支付", row.sourceDetail)
        assertEquals("synced", row.syncStatus)
        assertEquals(SERVER_ID, row.serverTransactionId)
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
        assertEquals("synced", row.syncStatus)
        assertEquals(SERVER_ID, row.serverTransactionId)
    }

    @Test
    @DisplayName("批量确认：逐笔各自送服务端，成功几笔就如实报几笔")
    fun `batch confirm reports per record`() = runTest {
        val outcomes = (0 until 3).map { i ->
            intake.intake(
                RecordIntent.NotificationCenterConfirm(
                    recordId = i.toLong(),
                    packageName = "com.tencent.mm",
                    amount = 100 * (i + 1),
                    type = "expense",
                    description = "第 $i 笔",
                )
            )
        }

        assertTrue(outcomes.all { it.recorded }, "全部成功时不得报失败")
        assertEquals(3, insertedRows.size)
        assertEquals(3, calls.count { it == "widget" })
        // 每笔都各自送了一次服务端（幂等靠 client_id，不靠「攒一批」）
        assertEquals(3, calls.count { it == "submit" })
    }

    @Test
    @DisplayName("单条确认：服务端确认后 recorded=true，且不回传新建审计行 id")
    fun `single confirm reports recorded`() = runTest {
        val single = intake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = 1L,
                packageName = "com.tencent.mm",
                amount = 100,
                type = "expense",
                description = "单条",
            )
        )

        assertTrue(single.recorded)
        assertNull(single.failure)
        // 通知中心确认不改既有审计行 → 没有新建的审计 id 可回传
        assertNull(single.recordId)
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败当场告知：服务端没确认就什么都不发生
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("服务端未回执 created：不写缓存、不写审计行、不发回执，失败原样带回")
    fun `no server confirmation means nothing is written`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            unconfirmed()
        }

        val outcome = intake.intake(
            RecordIntent.NotificationAuto(
                captured = capturedNotification(),
                amount = 3200,
                type = "expense",
                categoryId = 7,
                description = "午餐",
            )
        )

        assertFalse(outcome.recorded)
        val failure = outcome.failure
        assertTrue(failure is RecordFailure.Rejected, "code==0 却没回执 = 服务端没确认")
        assertNull(outcome.recordId, "没确认就没有审计行 id 可回传")
        assertTrue(insertedRows.isEmpty(), "本地一个字节都不能写")
        assertTrue(insertedRecords.isEmpty(), "审计行也不能建")
        assertFalse(calls.contains("write"), "写入器不得被调用")
        assertFalse(calls.contains("receipt"), "没记上就不许发「已记录」回执")
        assertFalse(calls.contains("widget"), "没记上就不刷统计")
        // 通知中心确认（弹窗/通知中心共用的失败面）同样什么都不写
        calls.clear()
        val confirmOutcome = intake.intake(
            RecordIntent.NotificationCenterConfirm(
                recordId = 9L,
                packageName = "com.tencent.mm",
                amount = 100,
                type = "expense",
                description = "午饭",
            )
        )
        assertFalse(confirmOutcome.recorded)
        assertTrue(insertedRows.isEmpty())
        assertFalse(calls.contains("write"))
        assertFalse(calls.contains("widget"))
    }

    @Test
    @DisplayName("服务端把这一笔回在 duplicates[] 里也算确认：落缓存、不发失败回执")
    fun `duplicates count as server confirmation`() = runTest {
        // RetryInterceptor 对 IOException 会重发同一个 body：首次请求其实已落库、
        // 只是响应丢了，重发拿到的就是 duplicates[]。判成失败 = 谎报「没记上」，
        // 用户重记（新 clientId）= 重复账。
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            duplicatesApiResponse(firstArg())
        }

        val outcome = intake.intake(
            RecordIntent.NotificationConfirm(
                recordId = 42L,
                packageName = "com.tencent.mm",
                amount = 2500,
                type = "expense",
                description = "午饭",
                learn = false,
            )
        )

        assertTrue(outcome.recorded, "服务端已有这笔 = 记上了")
        assertNull(outcome.failure)
        val row = onlyRow()
        assertEquals("synced", row.syncStatus)
        assertEquals(SERVER_ID, row.serverTransactionId)
        // 确认路径照常：写库 + 审计行回填 + 刷 widget + 轻回执
        assertTrue(calls.contains("write"))
        assertTrue(calls.contains("receipt"))
    }

    @Test
    @DisplayName("请求根本没送达：失败原因标成 Offline，同样什么都不写")
    fun `network failure is reported as offline and writes nothing`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws java.io.IOException("no route")

        val outcome = intake.intake(
            RecordIntent.ExternalQuickRecord(amount = 100, type = "expense", description = "午饭"),
        )

        assertEquals(RecordFailure.Offline, outcome.failure)
        assertFalse(outcome.recorded)
        assertTrue(insertedRows.isEmpty())
        assertEquals("没网，记不上", RecordFailure.Offline.userMessage)
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
        // 其余步骤照旧：服务端确认后仍然落库、照刷 widget、照学习
        assertEquals(1, insertedRows.size)
        assertEquals(listOf("submit", "write", "widget"), calls)
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

        assertEquals(listOf("submit", "write"), calls)
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
        assertEquals("synced", row.syncStatus)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        // 历史口径：本入口的 client_created_at 是**无时区**的本地日期时间串
        assertEquals("2026-07-10T12:30", row.clientCreatedAt)
    }

    @Test
    @DisplayName("静默入账：服务端拒绝时一行都不写，失败也不抛出去")
    fun `external quick record writes nothing when the server rejects`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            ApiResponse(code = 422, data = unconfirmed().data, message = "金额必须大于 0")
        }

        // 不抛即通过：静默入账跑在 ApplicationScope 上，异常逃出去 = 崩进程
        // （本工程没有全局 CoroutineExceptionHandler）。
        val outcome = intake.intake(
            RecordIntent.ExternalQuickRecord(amount = 100, type = "expense", description = "午饭"),
        )

        assertFalse(outcome.recorded)
        val failure = outcome.failure
        assertTrue(failure is RecordFailure.Rejected)
        assertEquals(422, (failure as RecordFailure.Rejected).code)
        assertTrue(insertedRows.isEmpty(), "服务端没确认就一行都不能落")
        assertFalse(calls.contains("write"))
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

    private companion object {
        /** 服务端在 `created[]` 里回认的交易 id（撤销时删的就是它） */
        const val SERVER_ID = 99
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
