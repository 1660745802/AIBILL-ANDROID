package com.aibill.android.data.record

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.data.local.entity.PendingTransactionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [DirectRecordWriter] 三步写入的用例（架构候选 3 补的覆盖缺口 A1）。
 *
 * ## 这个缺口长什么样
 *
 * `RecordWriter.applyRecordWrite` 的第三步
 * `notificationRecordDao.updateStatus(confirmId, "confirmed", clientId)`
 * 在收口（候选 1）之后**全仓无断言**：`RecordIntakeTest` 注入的是匿名
 * `object : RecordWriter`（它自己的类 KDoc 还写着「为什么用 DirectRecordWriter」），
 * 于是把第三步整段删掉，那个文件仍然全绿。
 *
 * ## 为什么丢不得这一步
 *
 * 它把 `notification_records.status` 从 `parsed` 推到 `confirmed`，
 * 而待审池只把非 confirmed 的行当作「待确认」。**丢了它 = 刚入账的通知重新出现在待确认
 * 列表里，用户再点一次确认就是重复账。** `RecordWriter` 的 KDoc 自己写着这是它存在的理由。
 *
 * ## 为什么要断言那个 clientId
 *
 * `updateStatus` 的第三个参数是 `linked_client_id`，它必须等于**写进
 * `pending_transactions.client_id` 的那一个**——两表关联键：
 * - 通知回执的「撤销」按它回查缓存行并删除。
 * 传错（甚至传成审计行自己的 id）会让撤销删不到那一行，于是出现幽灵账。
 *
 * ## 为什么用 mock DAO 而不是真实 Room
 *
 * 本类断言的是**调用顺序与参数**（三步、clientId 关联键），
 * mock 的调用序列表达力最强。真实 Room 那一层由 `LocalDataOwnershipImplTest`
 * （按表名数行、真实 insert/delete）覆盖。
 * ⚠️ 本类**不覆盖**事务原子性——那是生产 `RoomRecordWriter` 的 `withTransaction` 的事。
 */
class DirectRecordWriterTest {

    private val pendingDao: PendingTransactionDao = mockk(relaxed = true)
    private val notificationDao: NotificationRecordDao = mockk(relaxed = true)
    private val writer = DirectRecordWriter(pendingDao, notificationDao)

    private fun pending(clientId: String = "C-1") = PendingTransactionEntity(
        clientId = clientId,
        type = "expense",
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        time = "12:30",
        source = "app_notification",
        clientCreatedAt = "2026-07-05T04:00:00Z",
    )

    private fun capturedRecord() = NotificationRecordEntity(
        packageName = "com.tencent.mm",
        title = "微信支付",
        content = "支付成功 ¥32.00",
        parsedAmount = 3200,
        parsedType = "expense",
        parsedDescription = "午饭",
        status = "confirmed",
        receivedAt = 1_753_000_000_000L,
    )

    /**
     * AI 自动入账形态（新建审计行）：三步顺序 + **关联键必须是缓存行的 clientId**。
     *
     * 顺序：建审计行 → 写缓存行 → 回填审计行。
     * 反过来先写缓存行，就留下「账已在缓存、审计行还没回填」的缝——
     * 用户再点一次确认就是重复账。
     */
    @Test
    fun `auto intake writes audit row then cache row then backfills with the cache clientId`() = runTest {
        coEvery { notificationDao.insert(any()) } returns 7L
        val row = pending(clientId = "C-AUTO")

        val insertedId = writer.write(RecordWrite(pending = row, newRecord = capturedRecord()))

        assertEquals(7L, insertedId)
        coVerifyOrder {
            notificationDao.insert(any())
            pendingDao.insert(any())
            notificationDao.updateStatus(any(), any(), any())
        }
        // 关联键：回填用的 clientId 与写进 pending_transactions 的是同一个
        coVerify(exactly = 1) { notificationDao.updateStatus(7L, "confirmed", "C-AUTO") }
        coVerify(exactly = 1) { pendingDao.insert(row) }
    }

    /**
     * 弹窗/通知中心确认形态（审计行早已存在）：只回填既有 id，不新建。
     *
     * 漏掉这一步的后果与上一条相同：那条通知仍留在待审池里（status 还是 parsed），
     * 用户再点一次就是重复账。
     */
    @Test
    fun `confirm intake backfills the existing audit row with the cache row clientId`() = runTest {
        val row = pending(clientId = "C-CONFIRM")

        val insertedId = writer.write(RecordWrite(pending = row, confirmRecordId = 42L))

        assertNull(insertedId, "没有新建审计行时不该凭空造一个 id")
        coVerify(exactly = 0) { notificationDao.insert(any()) }
        coVerifyOrder {
            pendingDao.insert(any())
            notificationDao.updateStatus(any(), any(), any())
        }
        coVerify(exactly = 1) { notificationDao.updateStatus(42L, "confirmed", "C-CONFIRM") }
    }

    /**
     * 静默入账 / 外部 Intent 形态（两张表都不带审计行）：**不得**回填任何审计行。
     *
     * 这条防止反向变异：有人「顺手」让第三步在 confirmId 为 null 时也跑一次，
     * 拿 0 或上一条 id 去盖无关的通知。
     */
    @Test
    fun `silent intake never touches the audit table`() = runTest {
        writer.write(RecordWrite(pending = pending(clientId = "C-SILENT")))

        coVerify(exactly = 1) { pendingDao.insert(any()) }
        coVerify(exactly = 0) { notificationDao.insert(any()) }
        coVerify(exactly = 0) { notificationDao.updateStatus(any(), any(), any()) }
    }

    /**
     * clientId 不做任何加工：即便带中文/空格，回填用的也必须原封不动是缓存行那一个。
     *
     * 固化的是「不做任何转换」这条不变量：一旦有人在这里 trim、lowercase 或
     * 自己生成一个，回执的「撤销」就再也匹配不上那一行。
     */
    @Test
    fun `backfill passes the row's own clientId through untouched`() = runTest {
        coEvery { notificationDao.insert(any()) } returns 99L
        val row = pending(clientId = "客户端自己给的 clientId-中文")

        writer.write(RecordWrite(pending = row, newRecord = capturedRecord()))

        coVerify(exactly = 1) {
            notificationDao.updateStatus(99L, "confirmed", "客户端自己给的 clientId-中文")
        }
    }
}
