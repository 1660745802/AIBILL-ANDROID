package com.aibill.android.data.record

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.repository.AccountRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.mockk.verify
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
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.ProcessorRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.WechatRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.data.notification.InMemoryNotificationRuleSource
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.service.WidgetDataUpdater
import com.aibill.android.util.NotificationHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 入账 module（[RecordIntake]）的行为测试：**服务端先确认，本地才落库**。
 *
 * 4 个意图 subtype 是 4 条历史入账路径的全部差异，因此这里逐个钉：
 * 顺序（submit → write → confirm-record → widget → receipt）、
 * 只在服务端确认后写入、失败什么都不写、幂等（duplicates 算确认）、
 * locale 不变（ISO `YYYY-MM-DD`，不是佛历年 / 阿拉伯数字）。
 *
 * 三步写入与两表关联键（`updateStatus(confirmId, "confirmed", pending.clientId)`）
 * 由 [DirectRecordWriterTest] 直接驱动断言，本类注入匿名 `RecordWriter` 只关心 module 顺序。
 * locale 用注入时钟（2026-07-10T04:30:00Z = 12:30 +08:00）把「佛历年」的坑钉死。
 */
@DisplayName("RecordIntake：入账 module 的字段、顺序与 locale 不变")
class RecordIntakeTest {

    private val context: Context = mockk(relaxed = true)
    private val userPreferences: UserPreferences = mockk()
    private val learningEngine: CategoryLearningEngine = mockk(relaxed = true)
    private val streakTracker: StreakTracker = mockk(relaxed = true)
    private lateinit var ruleSource: NotificationRuleSource
    private val transactionApi: TransactionApi = mockk()
    private val notificationDao: NotificationRecordDao = mockk()
    private val accountRepository: AccountRepository = mockk()
    private val auditRows = mutableMapOf<Long, NotificationRecordEntity>()

    /** 送服务端 → 跨表写入 → 刷 widget → 回执 的调用顺序；每个 hook 都往里记一笔 */
    private val calls = mutableListOf<String>()
    private val insertedRows = mutableListOf<PendingTransactionEntity>()
    private val insertedRecords = mutableListOf<NotificationRecordEntity>()

    private val fixedZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant: Instant = Instant.parse("2026-07-10T04:30:00Z") // = 12:30 +08:00

    private lateinit var intake: RecordIntake
    private lateinit var writer: RecordWriter

    @BeforeEach
    fun setUp() {
        calls.clear()
        insertedRows.clear()
        insertedRecords.clear()
        auditRows.clear()
        every { accountRepository.observeAccounts() } returns flowOf(emptyList())
        coEvery { notificationDao.claimForConfirmation(any(), any()) } answers {
            val id = firstArg<Long>()
            val row = auditRows.getOrPut(id) {
                NotificationRecordEntity(
                    id = id, packageName = "com.tencent.mm", content = "支付成功",
                    status = "parsed", receivedAt = fixedInstant.toEpochMilli(),
                )
            }
            if (row.status == "parsed" && row.linkedClientId == null) {
                row.copy(linkedClientId = secondArg()).also { auditRows[id] = it }
            } else row
        }

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
        ruleSource = InMemoryNotificationRuleSource(
            NotificationRules(
                nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
                a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
                sms = SmsRules(emptyList()),
                sourceMapping = emptyMap(),
                processor = ProcessorRules(10, 60, emptyList(), emptyList(), 10_000_000, 1),
            )
        )

        val writer = object : RecordWriter {
            override suspend fun write(write: RecordWrite): Long? {
                calls += "write"
                val newId = write.newRecord?.also { insertedRecords += it }?.let { 7L }
                insertedRows += write.pending
                // 第三步（回填既有审计行）由 DirectRecordWriterTest 断言，这里只记顺序
                if (write.confirmRecordId != null || newId != null) calls += "confirm-record"
                write.confirmRecordId?.let { id ->
                    auditRows[id] = auditRows.getValue(id).copy(
                        status = "confirmed", linkedClientId = write.pending.clientId,
                    )
                }
                return newId
            }
        }
        this.writer = writer
        intake = buildIntake(writer)
    }

    /** 真实实现 + 换掉写入器（其余依赖仍来自 setUp 的 stub）。 */
    private fun buildIntake(writer: RecordWriter): RecordIntake {
        val clock = object : RecordClock {
            override fun now(): RecordMoment = RecordMoment.of(fixedInstant, fixedZone)
            override fun at(epochMillis: Long) =
                RecordMoment.of(java.time.Instant.ofEpochMilli(epochMillis), fixedZone)
        }

        return RecordIntakeImpl(
            context = context,
            recordWriter = writer,
            transactionApi = transactionApi,
            recordClock = clock,
            userPreferences = userPreferences,
            categoryLearningEngine = learningEngine,
            streakTracker = streakTracker,
            ruleSource = ruleSource,
            notificationRecordDao = notificationDao,
            accountRepository = accountRepository,
            captureSession = com.aibill.android.data.ownership.CaptureSession(),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WidgetDataUpdater)
        unmockkObject(NotificationHelper)
    }

    // ── 意图构造器（把用例正文压到只剩断言）───────────────────────────

    private fun autoIntent(
        amount: Int = 3200,
        categoryId: Int? = 7,
        description: String? = "午餐",
    ) = RecordIntent.NotificationAuto(
        captured = capturedNotification(),
        amount = amount,
        type = "expense",
        categoryId = categoryId,
        categoryName = "餐饮",
        categoryIcon = "🍜",
        description = description,
    )

    private fun confirmIntent(
        recordId: Long = 42L,
        amount: Int = 2500,
        description: String? = "午饭",
        learn: Boolean = false,
    ) = RecordIntent.NotificationConfirm(
        recordId = recordId,
        packageName = "com.tencent.mm",
        amount = amount,
        type = "expense",
        description = description,
        learn = learn,
    )

    private fun center(
        recordId: Long = 9L,
        amount: Int = 100,
        description: String? = "午饭",
    ) = RecordIntent.NotificationCenterConfirm(
        recordId = recordId,
        packageName = "com.tencent.mm",
        amount = amount,
        type = "expense",
        description = description,
    )

    /**
     * 通知捕获。
     *
     * ⚠️ `receivedAt` 默认取 [fixedInstant]：入账日期自 R-24 起取**通知到达时刻**（不是 `clock.now()`），
     * 两者不一致时会得到不同日期——测试要有意识地指定它。
     */
    private fun capturedNotification(receivedAt: Long = fixedInstant.toEpochMilli()) = CapturedNotification(
        packageName = "com.tencent.mm",
        title = "微信支付",
        content = "支付成功 ¥32.00",
        receivedAt = receivedAt,
    )

    /** 服务端回执：`code==0` + `created[]` 按 `client_id` 回认。 */
    private fun confirmed(request: CreateTransactionRequest): ApiResponse<CreateTransactionResponse> =
        ApiResponse(
            code = 0,
            data = CreateTransactionResponse(
                created = request.items.map { dtoFor(it.clientId, it.amount, it.type) },
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

    private fun dtoFor(clientId: String, amount: Int = 100, type: String = "expense") = TransactionDto(
        id = SERVER_ID,
        clientId = clientId,
        type = type,
        amount = amount,
        categoryId = null,
        categoryName = null,
        categoryIcon = null,
        accountId = null,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
        description = null,
        date = "2026-07-10",
        time = "12:30",
        tags = null,
        source = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun onlyRow(): PendingTransactionEntity {
        assertEquals(1, insertedRows.size, "应当只写一行本地缓存")
        return insertedRows.single()
    }

    private fun assertWroteNothing() {
        assertTrue(insertedRows.isEmpty(), "本地一个字节都不能写")
        assertTrue(insertedRecords.isEmpty(), "审计行也不能建")
        assertFalse(calls.contains("write"), "写入器不得被调用")
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 1：AI 自动入账
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AI 自动入账：先服务端确认→写库→刷 widget→回执→学习，且只此路径刷 streak")
    fun `auto keeps order and is the only path with streak and learning`() = runTest {
        coEvery { streakTracker.onTransactionRecorded() } returns StreakInfo()
        coEvery { learningEngine.matchCategory(any()) } returns 3

        val outcome = intake.intake(autoIntent())

        // 顺序：服务端确认 → 跨表写入（含回填审计行）→ 刷 widget → 发回执；学习在回执之后
        assertEquals(listOf("submit", "write", "confirm-record", "widget", "receipt"), calls)
        coVerify(exactly = 1) { streakTracker.onTransactionRecorded() }
        // 不预测：学习命中在 NotificationProcessor 里就算好了，挪进来会改变待审/入库判定
        coVerify(exactly = 0) { learningEngine.matchCategory(any()) }
        coVerify(exactly = 1) { learningEngine.learnFromCorrection("午餐", 7) }

        // 服务端确认后才写；本地缓存只装已确认的那一笔
        val row = onlyRow()
        assertEquals(outcome.clientId, row.clientId)
        assertTrue(row.clientId.isNotBlank(), "clientId 是幂等与撤销的唯一凭据")
        assertEquals("synced", row.syncStatus)
        assertEquals(SERVER_ID, row.serverTransactionId)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        assertEquals(fixedInstant.toString(), row.clientCreatedAt)
        assertNull(row.aiRawInput)

        // 新建审计行直接 confirmed，id 随结果回传（回执通知 id / 撤销定位键）
        assertEquals(7L, outcome.recordId)
        assertTrue(outcome.recorded)
        assertEquals(1, insertedRecords.size)
        val record = insertedRecords.single()
        assertEquals("confirmed", record.status)
        assertEquals(3200, record.parsedAmount)
        assertEquals("支付成功 ¥32.00", record.content)
    }

    @Test
    @DisplayName("回执隐私档：AI 自动读用户偏好，弹窗确认写死 false")
    fun `auto reads privacy preference while confirm hardcodes false`() = runTest {
        every { userPreferences.notificationPrivacy } returns flowOf(true)
        val privacy = slot<Boolean>()
        every {
            NotificationHelper.showAutoRecordedNotification(
                any(), any(), any(), any(), any(), capture(privacy), any(), any(),
            )
        } answers { calls += "receipt"; Unit }

        intake.intake(autoIntent())
        assertTrue(privacy.captured, "AI 自动入账必须读隐私档")

        intake.intake(confirmIntent())
        assertFalse(privacy.captured, "弹窗确认回执隐私档写死 false")
    }

    @Test
    @DisplayName("来源名映射：AI 自动走云控，弹窗确认走硬编码——历史差异保留")
    fun `auto reads the cloud source mapping while confirm uses the hardcoded one`() = runTest {
        ruleSource = InMemoryNotificationRuleSource(
            NotificationRules(
                nls = NlsRules("", WechatRules("", emptyList(), emptyList(), emptyList(), emptyList()), AlipayRules("", emptyList()), emptyList(), emptyList()),
                a11y = A11yRules(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", 5),
                sms = SmsRules(emptyList()),
                sourceMapping = mapOf("com.tencent.mm" to "微信云控"),
                processor = ProcessorRules(10, 60, emptyList(), emptyList(), 10_000_000, 1),
            )
        )
        // 重组 intake，使新的规则源生效（intake 在 setUp 里已捕获旧源）
        intake = buildIntake(writer)

        intake.intake(autoIntent())
        assertEquals("微信云控", onlyRow().sourceDetail)

        insertedRows.clear()
        intake.intake(confirmIntent())
        assertEquals("微信支付", insertedRows.single().sourceDetail)
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 2：弹窗确认 / 弹窗带备注确认
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("弹窗确认：先服务端确认再写库，预测分类但不学习，只写 category_id")
    fun `confirm keeps order and predicts without learning`() = runTest {
        coEvery { learningEngine.matchCategory("午饭") } returns 5

        intake.intake(confirmIntent())

        assertEquals(listOf("submit", "write", "confirm-record", "widget", "receipt"), calls)
        coVerify(exactly = 1) { learningEngine.matchCategory("午饭") }
        // handleConfirm 的历史行为就是不学习，与带备注确认的差异保留
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }

        val row = onlyRow()
        assertEquals(5, row.categoryId)
        // 只写分类 id，不写 name/icon（与手工记账 / 通知中心写冗余 name/icon 的差异保留）
        assertNull(row.categoryName)
        assertNull(row.categoryIcon)
        assertEquals("app_notification", row.source)
    }

    @Test
    @DisplayName("弹窗带备注确认：额外学习，且用 trim 后的描述")
    fun `confirm with note learns the trimmed description`() = runTest {
        coEvery { learningEngine.matchCategory("  公司食堂  ") } returns 5

        intake.intake(confirmIntent(description = "  公司食堂  ", learn = true))

        coVerify(exactly = 1) { learningEngine.learnFromCorrection("公司食堂", 5) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 3：通知中心单条 / 批量确认
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("通知中心确认：不预测不学习无回执，冗余写分类 name/icon，标签逗号拼接")
    fun `center confirm never predicts nor learns`() = runTest {
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

        // 无 "receipt"：用户已经在界面上看到结果
        assertEquals(listOf("submit", "write", "confirm-record", "widget"), calls)
        coVerify(exactly = 0) { learningEngine.matchCategory(any()) }
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }

        val row = onlyRow()
        assertEquals(3, row.categoryId)
        assertEquals("交通", row.categoryName)
        assertEquals("🚗", row.categoryIcon)
        assertEquals("出差,报销", row.tags)
    }

    @Test
    @DisplayName("批量确认：逐笔各自送服务端、成功几笔如实报几笔；无新建审计行 id 可回传")
    fun `batch confirm reports per record`() = runTest {
        val outcomes = (0 until 3).map { i ->
            intake.intake(center(recordId = i.toLong(), amount = 100 * (i + 1), description = "第 $i 笔"))
        }

        assertTrue(outcomes.all { it.recorded }, "全部成功时不得报失败")
        assertTrue(outcomes.all { it.recordId == null }, "通知中心确认不改既有审计行，没有 id 可回传")
        assertTrue(outcomes.all { it.failure == null })
        assertEquals(3, insertedRows.size)
        // 每笔都各自送了一次服务端（幂等靠 client_id，不靠「攒一批」）
        assertEquals(3, calls.count { it == "submit" })
        assertEquals(3, calls.count { it == "widget" })
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败当场告知：服务端没确认就什么都不发生
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("服务端没确认：业务错误 / 未回执 / 没网都一行不写、不发回执")
    fun `failure paths write nothing and report the reason`() = runTest {
        // code==0 却两个回执列表都没这条
        coEvery { transactionApi.createTransactions(any()) } answers { calls += "submit"; unconfirmed() }
        var outcome = intake.intake(autoIntent())
        assertTrue(outcome.failure is RecordFailure.Rejected, "code==0 却没回执 = 服务端没确认")
        assertNull(outcome.recordId, "没确认就没有审计行 id 可回传")
        assertWroteNothing()

        // 业务错误：code 原样带回
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            ApiResponse(code = 422, data = null, message = "金额必须大于 0")
        }
        outcome = intake.intake(center(recordId = 9L))
        assertEquals(422, (outcome.failure as RecordFailure.Rejected).code)
        assertWroteNothing()

        // 请求根本没送达
        coEvery { transactionApi.createTransactions(any()) } throws java.io.IOException("no route")
        outcome = intake.intake(RecordIntent.ExternalQuickRecord(amount = 100, type = "expense", description = "午饭"))
        assertEquals(RecordFailure.Offline, outcome.failure)
        assertEquals("没网，记不上", RecordFailure.Offline.userMessage)
        assertWroteNothing()

        assertFalse(calls.contains("receipt"), "没记上就不许发「已记录」回执")
        assertFalse(calls.contains("widget"), "没记上就不刷统计")
    }

    @Test
    @DisplayName("duplicates 按 client_id 匹配才算确认：匹配=记上，不匹配=没确认")
    fun `duplicates count only when the clientId matches`() = runTest {
        // RetryInterceptor 对 IOException 重发同一 body：首次其实已落库，重发拿到 duplicates[]
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            duplicatesApiResponse(firstArg())
        }

        val outcome = intake.intake(confirmIntent())

        assertTrue(outcome.recorded, "服务端已有这笔 = 记上了")
        val row = onlyRow()
        // 两表关联键：写进缓存的 clientId 就是服务端回认、撤销定位用的那一个
        assertEquals(outcome.clientId, row.clientId)
        assertEquals("synced", row.syncStatus)
        assertEquals(SERVER_ID, row.serverTransactionId)
        assertTrue(calls.contains("write"))
        assertTrue(calls.contains("receipt"))

        // duplicates 里是**别的** clientId：不算确认，什么都不写
        calls.clear()
        insertedRows.clear()
        insertedRecords.clear()
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            ApiResponse(
                code = 0,
                data = CreateTransactionResponse(created = emptyList(), duplicates = listOf(dtoFor("someone-else"))),
                message = "ok",
            )
        }
        val missed = intake.intake(confirmIntent(recordId = 43L))
        assertTrue(missed.failure is RecordFailure.Rejected, "匹配必须按 client_id，不能只看 duplicates 非空")
        assertWroteNothing()
    }

    @Test
    @DisplayName("判定顺序：业务错误码优先于回执匹配")
    fun `business error code wins over a matching receipt`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } answers {
            calls += "submit"
            val request = firstArg<CreateTransactionRequest>()
            ApiResponse(
                code = 5001,
                data = CreateTransactionResponse(created = listOf(dtoFor(request.items[0].clientId)), duplicates = emptyList()),
                message = "系统繁忙",
            )
        }

        val outcome = intake.intake(confirmIntent())

        // 业务码分支必须先于回执匹配：否则一条 code!=0 的回执里若碰巧带了匹配 id，
        // 就会被当成「记上了」。服务端 message 也要原样透出。
        assertEquals(5001, (outcome.failure as RecordFailure.Rejected).code)
        assertEquals("系统繁忙", (outcome.failure as RecordFailure.Rejected).reason)
        assertWroteNothing()
    }

    @Test
    @DisplayName("HTTP 错误：映射成携带 HTTP 状态码的 Rejected，不谎报没网")
    fun `http failure is rejected with its http code`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws
            HttpException(Response.error<Any>(500, "{}".toResponseBody(null)))

        val outcome = intake.intake(autoIntent())

        assertEquals(500, (outcome.failure as RecordFailure.Rejected).code)
        assertWroteNothing()
    }

    @Test
    @DisplayName("取消必须原样传播，不能被当成记账失败吞掉")
    fun `cancellation propagates instead of being swallowed`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws CancellationException("cancelled")

        var propagated: CancellationException? = null
        try {
            intake.intake(autoIntent())
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull(propagated, "取消必须原样抛出")
        assertWroteNothing()
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

        val outcome = buildIntake(silentWriter).intake(autoIntent())

        assertNull(outcome.recordId, "拿不到 id 就如实返回 null")
        assertFalse(calls.contains("receipt"), "没有 id 就不发回执：宁可没有回执，也不能盖掉真实通知")
        // 其余步骤照旧：服务端确认后仍然落库、照刷 widget、照学习
        assertEquals(1, insertedRows.size)
        assertEquals(listOf("submit", "write", "widget"), calls)
    }

    // ═══════════════════════════════════════════════════════════════
    // 意图 4：外部 Intent 静默入账
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("静默入账：来源标记 tasker_quick_record、无时区 client_created_at、不刷 widget")
    fun `external quick record keeps its own payload`() = runTest {
        val outcome = intake.intake(
            RecordIntent.ExternalQuickRecord(amount = 2590, type = "expense", description = "咖啡")
        )

        assertEquals(listOf("submit", "write"), calls)
        // 不刷 widget、不翻审计表（其余通知路径都刷）
        assertFalse(calls.contains("widget"))
        assertTrue(insertedRecords.isEmpty())

        val row = onlyRow()
        assertEquals(2590, row.amount)
        assertEquals("expense", row.type)
        assertEquals("咖啡", row.description)
        assertEquals(outcome.clientId, row.clientId)
        assertTrue(row.clientId.isNotBlank())
        assertEquals("app_notification", row.source)
        // 来源标记的具体值：tasker_quick_record 是 AutoRecord 与本入口的区分依据
        assertEquals("tasker_quick_record", row.sourceDetail)
        assertEquals("synced", row.syncStatus)
        assertEquals("2026-07-10", row.date)
        assertEquals("12:30", row.time)
        // 历史口径：本入口的 client_created_at 是**无时区**的本地日期时间串
        assertEquals("2026-07-10T12:30", row.clientCreatedAt)
    }

    /**
     * 服务端 `POST /api/transactions` 的 `items[].source` 允许值。
     *
     * 来源：`server/src/routes/transaction.ts` 的 zod enum（`manual|ai|import_csv|app_notification|ocr|subscription`）；
     * DB 的 `transactions.source` 还有 CHECK 约束，枚举外的值即使绕过 zod 也会写失败。
     * 服务端改枚举时必须同步这里——本用例就是 `external_intent` 那次漏网的护栏。
     */
    private val allowedRequestSources = setOf(
        "manual", "ai", "import_csv", "app_notification", "ocr", "subscription",
    )

    @Test
    @DisplayName("护栏：外部 Intent 路径的 source 必须在服务端允许枚举内（防 external_intent 回归）")
    fun `external quick record source is in server allowed enum`() = runTest {
        val body = slot<CreateTransactionRequest>()
        coEvery { transactionApi.createTransactions(capture(body)) } answers {
            calls += "submit"
            confirmed(firstArg())
        }

        intake.intake(RecordIntent.ExternalQuickRecord(amount = 2590, type = "expense", description = "咖啡"))

        val source = body.captured.items.first().source
        assertTrue(
            source in allowedRequestSources,
            "外部 Intent 的 source=$source 不在服务端允许枚举内 → POST /transactions 恒定 400/500",
        )
        assertEquals("app_notification", source)
    }

    // ═══════════════════════════════════════════════════════════════
    // 回执策略 + locale 不变
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("回执策略由意图决定：auto / confirm-receipt / none")
    fun `receipt policy is decided by the intent`() {
        assertEquals(ReceiptPolicy.AUTO, autoIntent().receipt)
        assertEquals(ReceiptPolicy.CONFIRM_RECEIPT, confirmIntent().receipt)
        assertEquals(ReceiptPolicy.NONE, center().receipt)
        assertEquals(ReceiptPolicy.NONE, RecordIntent.ExternalQuickRecord(1, "expense", null).receipt)
    }

    @Test
    @DisplayName("泰语 / 阿拉伯语 locale 下落库日期仍是 ISO 公历、ASCII 数字")
    fun `non gregorian locales still yield ISO ascii date and time`() = runTest {
        mapOf(
            Locale("th", "TH") to "2569", // 佛历年
            Locale("ar", "EG") to "٢٠٢٦", // 阿拉伯数字
        ).forEach { (locale, localizedYear) ->
            insertedRows.clear()
            auditRows.clear()
            withDefaultLocale(locale) {
                intake.intake(confirmIntent())
            }

            val row = onlyRow()
            val date = row.date
            assertFalse(date.startsWith(localizedYear), "locale=$locale 落库日期不能本地化：$date")
            assertEquals("2026-07-10", date)
            assertEquals("12:30", row.time)
            assertTrue(date.all { it.code < 128 }, "落库日期必须全是 ASCII：$date")
            assertTrue((row.time ?: "").all { it.code < 128 }, "落库时间必须全是 ASCII：${row.time}")
            // 服务端按 ISO YYYY-MM-DD 解析
            assertEquals(2026, java.time.LocalDate.parse(date).year)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `confirm note and center serialize the same record and reuse its result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { transactionApi.createTransactions(any()) } coAnswers {
            calls += "submit"
            gate.await()
            confirmed(firstArg())
        }
        val first = async { intake.intake(confirmIntent()) }
        runCurrent()
        val note = async { intake.intake(confirmIntent(learn = true)) }
        val centerResult = async { intake.intake(center(recordId = 42L)) }
        runCurrent()
        assertEquals(1, calls.count { it == "submit" })
        gate.complete(Unit)
        val outcomes = listOf(first.await(), note.await(), centerResult.await())
        assertTrue(outcomes.all { it.recorded })
        assertEquals(1, outcomes.map { it.clientId }.distinct().size)
        assertEquals(2, outcomes.count { it.alreadyRecorded })
        assertEquals(1, calls.count { it == "write" })
        assertEquals(1, calls.count { it == "widget" })
        assertEquals(1, calls.count { it == "receipt" })
        coVerify(exactly = 0) { learningEngine.learnFromCorrection(any(), any()) }
    }

    @Test
    fun `lost response retry after intake recreation keeps persisted key without early confirmation`() = runTest {
        var lostKey: String? = null
        lateinit var lostRequest: CreateTransactionRequest
        coEvery { transactionApi.createTransactions(any()) } answers {
            lostRequest = firstArg()
            lostKey = lostRequest.items.single().clientId
            assertEquals(lostKey, auditRows.getValue(42L).linkedClientId)
            assertEquals("parsed", auditRows.getValue(42L).status)
            throw java.io.IOException("response lost")
        }
        val failed = intake.intake(confirmIntent())
        assertFalse(failed.recorded)
        assertWroteNothing()
        assertEquals("parsed", auditRows.getValue(42L).status)
        coEvery { transactionApi.createTransactions(any()) } answers {
            assertEquals(lostKey, firstArg<CreateTransactionRequest>().items.single().clientId)
            duplicatesApiResponse(lostRequest)
        }
        val retried = buildIntake(writer).intake(center(recordId = 42L))
        assertTrue(retried.recorded)
        assertEquals(failed.clientId, retried.clientId)
        assertEquals(2500, onlyRow().amount)
        verify(exactly = 1) { WidgetDataUpdater.notifyTransactionAdded(any(), any(), 2500, any()) }
        assertEquals("confirmed", auditRows.getValue(42L).status)
    }

    @Test
    fun `cancelled confirmation releases lock but keeps its retry key`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws CancellationException("cancelled")
        var cancelled = false
        try {
            intake.intake(confirmIntent())
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        val reserved = auditRows.getValue(42L)
        assertEquals("parsed", reserved.status)
        assertNotNull(reserved.linkedClientId)
        coEvery { transactionApi.createTransactions(any()) } answers { confirmed(firstArg()) }
        val retried = intake.intake(center(recordId = 42L))
        assertTrue(retried.recorded)
        assertEquals(reserved.linkedClientId, retried.clientId)
    }

    @Test
    fun `confirmed record survives intake recreation without post widget or receipt`() = runTest {
        val recorded = intake.intake(center(recordId = 42L))
        calls.clear()
        val repeated = buildIntake(writer).intake(confirmIntent(learn = true))
        assertTrue(repeated.alreadyRecorded)
        assertEquals(recorded.clientId, repeated.clientId)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `missing ignored and raw audit records never submit`() = runTest {
        coEvery { notificationDao.claimForConfirmation(42L, any()) } returns null
        assertFalse(intake.intake(confirmIntent()).recorded)
        for (status in listOf("ignored", "raw", "auth_pending")) {
            auditRows[9L] = NotificationRecordEntity(
                id = 9L, packageName = "com.tencent.mm", content = "x", status = status,
            )
            assertFalse(intake.intake(center()).recorded)
        }
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
    }

    @Test
    fun `manual notification confirmations use audit receivedAt not current time`() = runTest {
        val received = Instant.parse("2026-07-09T15:40:00Z")
        for (id in listOf(42L, 9L)) {
            auditRows[id] = NotificationRecordEntity(
                id = id, packageName = "com.tencent.mm", content = "x", status = "parsed",
                receivedAt = received.toEpochMilli(),
            )
        }
        intake.intake(confirmIntent())
        intake.intake(center())
        assertEquals(2, insertedRows.size)
        insertedRows.forEach {
            assertEquals("2026-07-09", it.date)
            assertEquals("23:40", it.time)
            assertEquals(received.toString(), it.clientCreatedAt)
        }
    }

    @Test
    fun `center carries source and target accounts through request and cache`() = runTest {
        every { accountRepository.observeAccounts() } returns flowOf(listOf(
            com.aibill.android.domain.model.Account(id = 1, name = "银行卡", type = "bank", icon = "bank"),
            com.aibill.android.domain.model.Account(id = 2, name = "现金", type = "cash", icon = "cash"),
        ))
        val body = slot<CreateTransactionRequest>()
        coEvery { transactionApi.createTransactions(capture(body)) } answers { confirmed(firstArg()) }
        val result = intake.intake(center().copy(type = "transfer", accountId = 1, targetAccountId = 2))
        assertTrue(result.recorded)
        assertEquals(1, onlyRow().accountId)
        assertEquals(2, onlyRow().targetAccountId)
        assertEquals(1, body.captured.items.single().accountId)
        assertEquals(2, body.captured.items.single().targetAccountId)
    }

    @Test
    fun `invalid transfer accounts require editing without posting`() = runTest {
        for ((source, target) in listOf(null to null, 1 to 1, 1 to 999)) {
            val outcome = intake.intake(center().copy(type = "transfer", accountId = source, targetAccountId = target))
            assertFalse(outcome.recorded)
            assertTrue(outcome.failure!!.userMessage.contains("编辑"))
        }
        assertFalse(intake.intake(confirmIntent().copy(type = "transfer")).recorded)
        coVerify(exactly = 0) { transactionApi.createTransactions(any()) }
        verify(exactly = 0) { WidgetDataUpdater.notifyTransactionAdded(any(), any(), any(), any()) }
    }

    private companion object {
        /** 服务端在 `created[]` 里回认的交易 id（撤销时删的就是它） */
        const val SERVER_ID = 99
    }

    @Test
    @DisplayName("R-24 回归：日期取**通知到达日**，不是解析/入账时刻（跨午夜不再记错一天）")
    fun `跨午夜迟发落在通知当天`() = runTest {
        // 通知在 07-09 23:40(+08:00) 到达；固定时钟（= 解析/入账那一刻）是 07-10 12:30(+08:00)。
        // 服务端已按 received_at 锚定「今天」；入账若仍取 clock.now()，这笔会被记到 07-10。
        val notificationAt = java.time.Instant.parse("2026-07-09T15:40:00Z")

        intake.intake(
            autoIntent().copy(captured = capturedNotification(receivedAt = notificationAt.toEpochMilli()))
        )

        val row = onlyRow()
        assertEquals("2026-07-09", row.date, "日期必须取通知到达日，而不是入账/解析那天")
        assertEquals("23:40", row.time)
        assertEquals(notificationAt.toString(), row.clientCreatedAt)
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
