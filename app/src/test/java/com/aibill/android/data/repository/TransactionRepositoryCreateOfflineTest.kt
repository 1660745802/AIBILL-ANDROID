package com.aibill.android.data.repository

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.data.record.DirectRecordWriter
import com.aibill.android.data.record.RecordClock
import com.aibill.android.data.record.RecordIntakeImpl
import com.aibill.android.data.record.RecordMoment
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.domain.usecase.CategoryLearningEngine
import com.aibill.android.domain.usecase.StreakTracker
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.SyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 锁 [TransactionRepositoryImpl.createTransactionOffline] 的两条新契约。
 *
 * ## 为什么单独一个文件（而不是追加进 `TransactionRepositorySyncPendingTest`）
 *
 * 那个文件的主题是**「谁触发的同步」**（`syncPending()` 的入队/错误契约），
 * 本文件的主题是**「离线落库之后发生了什么」**（落库 + 排调度 + 失败不回滚）。
 * 两者断言互不相干；挤在一起会让后来者改同步触发逻辑时顺手看坏离线落库语义。
 *
 * ## 为什么要 mock `SyncScheduler`（object，不是 static）
 *
 * `SyncScheduler` 是 Kotlin `object`（非 Java 静态方法），因此用 `mockkObject`
 * 而不是 `mockkStatic`——基建直接照抄 `TransactionRepositorySyncPendingTest`。
 * 不需要真实 WorkManager，也就不碰 Robolectric/WorkManager 测试驱动。
 *
 * ## 架构候选 1 之后的变化（不是行为变化）
 *
 * `createTransactionOffline` 现在只把 `Transaction` 翻译成入账意图交给
 * [com.aibill.android.domain.record.RecordIntake]，本类组装**真实的**入账实现
 * （落库走 `DirectRecordWriter`，只把 Room 事务换成直写），断言仍打在真实实现上。
 *
 * ## Success = 已入队，不是已同步
 *
 * 本类任何文案/断言都**不得**出现「已同步 / 已上传」：`scheduleSyncIfNeeded` 只是
 * 把 `SyncWorker` 入队（`NetworkType.CONNECTED` + unique work 串行），真实上传结果
 * 由队列状态与横幅呈现。把入队当成功去改写用户文案 = 新谎报。
 */
class TransactionRepositoryCreateOfflineTest {

    private val context: Context = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk()
    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val notificationRecordDao: NotificationRecordDao = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SyncScheduler)
    }

    private fun repo() = TransactionRepositoryImpl(
        transactionApi = transactionApi,
        pendingTransactionDao = pendingDao,
        recordIntake = intake(),
        context = context,
    )

    /**
     * 真实的入账 module，只把落库换成 [DirectRecordWriter]（不包 Room 事务）——
     * 于是断言仍然打在真实的入账实现上，而不是 mock 掉入账入口。
     */
    private fun intake(): RecordIntake = RecordIntakeImpl(
        context = context,
        recordWriter = DirectRecordWriter(pendingDao, notificationRecordDao),
        recordClock = object : RecordClock {
            override fun now(): RecordMoment = RecordMoment.system()
        },
        userPreferences = mockk(relaxed = true),
        categoryLearningEngine = mockk<CategoryLearningEngine>(relaxed = true),
        streakTracker = mockk<StreakTracker>(relaxed = true),
        rulesManager = mockk<NotificationRulesManager>(relaxed = true),
    )

    private fun transaction(clientId: String = "C-offline-1") = Transaction(
        clientId = clientId,
        type = TransactionType.EXPENSE,
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        time = "12:30",
    )

    /**
     * 核心不变量：落离线后**必须**排一次同步，否则这条 pending 只能等网络跳变 /
     * 启动 / 用户手动点——WiFi 常连的瞬时失败会一直躺着。
     *
     * 同时用 `coVerifyOrder` 钉住顺序：**先 insert、后 schedule**。
     * 反过来可能让 worker 早于本行提交跑到空队列。
     */
    @Test
    fun `createTransactionOffline inserts then schedules a sync`() = runTest {
        repo().createTransactionOffline(transaction())

        coVerifyOrder {
            pendingDao.insert(any())
            SyncScheduler.scheduleSyncIfNeeded(context)
        }
    }

    /** 调度失败**绝不回滚**：账已落库，deleteAll 会直接丢用户数据。 */
    @Test
    fun `scheduling failure never rolls back the stored transaction`() = runTest {
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } throws RuntimeException("WM down")

        // 不抛给调用方：异常若逃出 viewModelScope 会崩进程（本工程无全局
        // CoroutineExceptionHandler），且会让 ManualRecordViewModel 跳过 isSaving 复位
        repo().createTransactionOffline(transaction())

        coVerify(exactly = 1) { pendingDao.insert(any()) }
        coVerify(exactly = 1) { SyncScheduler.scheduleSyncIfNeeded(context) }
        coVerify(exactly = 0) { pendingDao.deleteAll() }
        coVerify(exactly = 0) { pendingDao.deleteByClientId(any()) }
    }

    /** 落库的内容不受调度影响：仍是 syncStatus=pending、带 clientId 的一行。 */
    @Test
    fun `stored row keeps its own payload regardless of scheduling`() = runTest {
        val slot = mutableListOf<PendingTransactionEntity>()
        coEvery { pendingDao.insert(capture(slot)) } returns Unit

        repo().createTransactionOffline(transaction("C-payload"))

        assertEquals(1, slot.size)
        assertEquals("C-payload", slot[0].clientId)
        assertEquals("pending", slot[0].syncStatus)
        assertEquals(3200, slot[0].amount)
    }
}
