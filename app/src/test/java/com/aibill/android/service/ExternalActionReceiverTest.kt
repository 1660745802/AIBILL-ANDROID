package com.aibill.android.service

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `ExternalActionReceiver.handleAutoConfirmRecord`（静默入账本体）的不变量测试。
 *
 * ## 为什么测这个函数而不是 `onReceive`
 *
 * `Hilt_ExternalActionReceiver` 在 `onReceive` 与 `goAsync()` 两处都会 `inject()`，
 * 要求 Application 是 `@HiltAndroidApp`；本工程的 Robolectric 基建给不出这种
 * Application：`application = Application::class` 抛 Hilt 前置条件，
 * `application = AiBillApp::class` 抛 `KeyStoreException: AndroidKeyStore not found`
 * （引入 `hilt-android-testing` 不在本轮范围）。
 * 故 `handleAutoConfirmRecord` 被提为 `internal`（与 `AutoRecordActionReceiver.handleUndo`
 * 同款先例），测试直接调它，绕开 Hilt/goAsync，也因此**不需要 Robolectric**。
 *
 * ## 锁什么
 *
 * 1. 写库后**必须**排一次同步，且顺序是 insert → syncPending。
 *    历史缺口：这里只写库，排同步全靠网络跳变 / 启动 / 用户手动点，
 *    WiFi 常连时这条 pending 会一直躺着。
 * 2. `syncPending` 返回 `Result.Error` 时**不抛**，且记账**不受影响**
 *    （账已落库，不能回滚 = 不能丢用户数据）。
 *
 * ## ⚠️ 覆盖缺口（诚实声明，不要夸大）
 *
 * 本测试**不覆盖**这两条，它们仍在 `onReceive` 内、受 Hilt 门禁阻挡、
 * 在当前基建下**不可测**（且本切片**未改动**它们，靠 code review 把守）：
 *
 * - 「权限被拒时既不写库也不排同步」
 * - 「`autoConfirm=false` 时既不写库也不排同步」
 *
 * 也不要以为本类验证了权限安全——它完全没碰 `checkPermission`。
 *
 * ## 为什么不测「syncPending 抛异常」
 *
 * 生产实现 `TransactionRepositoryImpl.syncPending()` 内部已 `catch (Exception)`
 * 并返回 `Result.Error`，所以真实的 `syncPending` **不会抛**。
 * 本函数因此没有再加一层 try/catch（那会把「编程错误」也静默掉）。
 * mock 抛异常属于**测试构造出来的伪场景**，不构成需要锁的契约，故跳过该用例。
 *
 * ## Success = 已入队，不是已同步
 *
 * `syncPending` 只是把 `SyncWorker` 入队（`NetworkType.CONNECTED` + unique work 串行），
 * 真实上传结果由队列状态与横幅呈现。不得据此在生产代码里改成「已同步 / 已上传」类文案。
 */
class ExternalActionReceiverTest {

    private lateinit var pendingDao: PendingTransactionDao
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var receiver: ExternalActionReceiver

    @BeforeEach
    fun setUp() {
        pendingDao = mockk(relaxed = true)
        transactionRepository = mockk(relaxed = true)
        coEvery { transactionRepository.syncPending() } returns Result.Success(Unit)

        receiver = ExternalActionReceiver()
        receiver.pendingTransactionDao = pendingDao
        receiver.transactionRepository = transactionRepository
    }

    /**
     * 写库后必须排同步，且**先写库后排队**：反过来可能让 worker 早于本行提交
     * 跑到空队列（白跑一轮，还要等下一次跳变才补传）。
     */
    @Test
    fun `auto confirm record - inserts then schedules a sync`() = runTest {
        receiver.handleAutoConfirmRecord(
            amount = 100,
            type = "expense",
            description = "午饭",
        )

        coVerifyOrder {
            pendingDao.insert(any())
            transactionRepository.syncPending()
        }
    }

    /**
     * 同步入队失败：**不抛**（`goAsync` 的 `finish()` 由 `finally` 保证，不受影响；
     * 真正的风险是异常逃出 `ApplicationScope` 后**崩进程**——本工程无全局
     * `CoroutineExceptionHandler`），且记账已落库不受影响——insert 仍恰好 1 次。
     */
    @Test
    fun `scheduling failure - does not throw and leaves the stored transaction alone`() = runTest {
        coEvery { transactionRepository.syncPending() } returns Result.Error(5001, "WM down")

        // 不抛即通过；下面两条断言把「不影响记账」钉死
        receiver.handleAutoConfirmRecord(
            amount = 100,
            type = "expense",
            description = "午饭",
        )

        coVerify(exactly = 1) { pendingDao.insert(any()) }
        coVerify(exactly = 1) { transactionRepository.syncPending() }
    }

    /** 写入内容本身：静默入账的来源标记与金额不能被同步逻辑改动。 */
    @Test
    fun `stored row keeps the external intent payload`() = runTest {
        val slot = mutableListOf<PendingTransactionEntity>()
        coEvery { pendingDao.insert(capture(slot)) } returns Unit

        receiver.handleAutoConfirmRecord(amount = 2590, type = "expense", description = "咖啡")

        assertEquals(1, slot.size)
        assertEquals(2590, slot[0].amount)
        assertEquals("expense", slot[0].type)
        assertEquals("咖啡", slot[0].description)
        assertEquals("external_intent", slot[0].source)
        // 来源标记的**具体值**：tasker_quick_record 是 AutoRecord 与本入口的区分依据，
        // 丢了它两类记录在服务端就分不出来。
        assertEquals("tasker_quick_record", slot[0].sourceDetail)
        // clientId 是撤销与幂等的唯一凭据（AutoRecordActionReceiver 按它查行），
        // 空值会让这笔账永远无法被撤销。
        assertTrue(slot[0].clientId.isNotBlank())
        assertEquals("pending", slot[0].syncStatus)
    }
}
