package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 通知中心读路径的行为护栏（P0-1「忽略可找回」+ P3-4「角标只计可审」）。
 *
 * 只改查询、不写迁移：`observeReviewItems` 把窗口内的 `ignored` 也读出来（供「已忽略」区
 * 展示与恢复）；`observePendingCount` 只数 `parsed`（历史 `raw` 没有金额/分类，审不了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationRecordReviewQueryTest {

    private lateinit var db: AppDatabase
    private val now = 1_700_000_000_000L
    private val windowMs = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(status: String, receivedAt: Long, content: String = status) = runBlocking {
        db.notificationRecordDao().insert(
            NotificationRecordEntity(
                packageName = "com.tencent.mm",
                content = content,
                status = status,
                receivedAt = receivedAt,
            )
        )
    }

    @Test
    fun `confirmation claim atomically retains one key while status remains parsed`() = runBlocking {
        val dao = db.notificationRecordDao()
        val id = insert("parsed", now)
        val rows = (1..8).map { index ->
            async(Dispatchers.Default) { dao.claimForConfirmation(id, "client-$index")!! }
        }.awaitAll()
        assertEquals(1, rows.map { it.linkedClientId }.distinct().size)
        assertEquals(setOf("parsed"), rows.map { it.status }.toSet())
        val key = rows.first().linkedClientId
        assertEquals(key, dao.claimForConfirmation(id, "retry")!!.linkedClientId)
        dao.updateStatus(id, "ignored")
        dao.updateStatus(id, "parsed")
        assertEquals(key, dao.claimForConfirmation(id, "restored")!!.linkedClientId)
        dao.updateStatus(id, "confirmed", key)
        dao.updateStatus(id, "ignored")
        dao.updateStatus(id, "parsed")
        assertEquals("confirmed", dao.findById(id)!!.status)
        assertEquals(key, dao.claimForConfirmation(id, "later")!!.linkedClientId)
        val ignored = insert("ignored", now)
        assertNull(dao.claimForConfirmation(ignored, "unused")!!.linkedClientId)
        assertNull(dao.claimForConfirmation(-1L, "missing"))
    }

    @Test
    fun `same amount query is bounded by candidate time in both directions`() = runBlocking {
        val dao = db.notificationRecordDao()
        for ((index, offset) in listOf(-60_001L, -60_000L, 0L, 60_000L, 60_001L).withIndex()) {
            val amount = index + 100
            val id = dao.insert(NotificationRecordEntity(
                packageName = "cmb.pb", content = "payment", status = "parsed",
                parsedAmount = amount, receivedAt = now + offset,
            ))
            val match = dao.findRecentSameAmount(amount, now - 60_000, now + 60_000)
            assertEquals(if (offset in -60_000L..60_000L) id else null, match?.id)
        }
        dao.insert(NotificationRecordEntity(
            packageName = "cmb.pb", content = "old", status = "confirmed",
            parsedAmount = 999, receivedAt = now,
        ))
        assertNull(dao.findRecentSameAmount(999, now - 3_660_000, now - 3_540_000))
        dao.insert(NotificationRecordEntity(
            packageName = "cmb.pb", content = "ignored", status = "ignored",
            parsedAmount = 998, receivedAt = now,
        ))
        assertNull(dao.findRecentSameAmount(998, now - 60_000, now + 60_000))
    }

    @Test
    fun `observeReviewItems 返回待审 + 窗口内已确认 + 窗口内已忽略，排除窗口外的终态`() {
        insert("parsed", now)
        insert("raw", now)
        insert("confirmed", now)                       // 窗口内 → 要
        insert("confirmed", now - windowMs - 1)        // 窗口外 → 排除
        insert("ignored", now)                         // 窗口内 → 要（可找回）
        insert("ignored", now - windowMs - 1)          // 窗口外 → 排除

        val since = now - windowMs
        val items = runBlocking { db.notificationRecordDao().observeReviewItems(since, since).first() }

        assertEquals(
            listOf("confirmed", "ignored", "parsed", "raw"),
            items.map { it.status }.sorted(),
        )
    }

    @Test
    fun `observePendingCount 只数可审的 parsed（历史 raw 不计入角标）`() {
        insert("parsed", now, content = "a")
        insert("parsed", now, content = "b")
        insert("raw", now, content = "c")       // 不可审 → 不计
        insert("ignored", now, content = "d")
        insert("confirmed", now, content = "e")

        val count = runBlocking { db.notificationRecordDao().observePendingCount().first() }
        assertEquals(2, count)
    }
}
