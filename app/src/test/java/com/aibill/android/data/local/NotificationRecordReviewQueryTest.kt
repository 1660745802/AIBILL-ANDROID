package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.db.AppDatabase
import com.aibill.android.data.local.entity.NotificationRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
