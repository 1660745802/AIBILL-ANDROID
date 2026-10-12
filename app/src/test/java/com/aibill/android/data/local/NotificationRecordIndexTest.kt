package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `notification_records` 热路径索引的行为测试（ARCHITECTURE §12.1#3）。
 *
 * 这张表只增不减，且每条通知都会命中两条查询：
 * - DB 1s 去重 `package_name + content + received_at`
 * - 跨渠道判重 `parsed_amount + status + received_at`
 *
 * 没有索引就是全表扫描，成本随使用时长线性上升。本测试不逐字复述 SQL，而是：
 * 1. 断言真实建出的库里确实有那两条按 Room 约定命名的复合索引（删索引立刻红）；
 * 2. 用 `EXPLAIN QUERY PLAN` 断言两条热路径查询走的是 **SEARCH ... USING INDEX**，
 *    而不是全表扫描（索引列序写错、索引没用上也会红）。
 *
 * 说明：迁移 SQL（`Migration(8, 9)`）本身由 Room 在运行时做 schema 校验（名字/列序
 * 不一致会崩），本仓库没有 androidTest 基建，故不在此重复断言迁移字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationRecordIndexTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `entity 声明的两条复合索引在真实建库后全部存在`() {
        val expected = setOf(
            namedIndex("package_name", "content", "received_at"),
            namedIndex("parsed_amount", "status", "received_at"),
        )
        assertEquals(expected, indexNames())
    }

    @Test
    fun `DB 1s 去重查询走 package_name+content+received_at 索引`() {
        assertUsesIndex(
            sql = "SELECT * FROM notification_records " +
                "WHERE package_name = ? AND content = ? AND received_at > ? LIMIT 1",
            args = arrayOf<Any?>("com.tencent.mm", "支付成功 ¥32.00", 0L),
            expectedIndex = namedIndex("package_name", "content", "received_at"),
        )
    }

    @Test
    fun `跨渠道判重查询走 parsed_amount+status+received_at 索引`() {
        assertUsesIndex(
            sql = "SELECT * FROM notification_records " +
                "WHERE parsed_amount = ? AND status IN ('confirmed', 'parsed') " +
                "AND received_at > ? AND package_name != ? LIMIT 1",
            args = arrayOf<Any?>(3200, 0L, "com.tencent.mm"),
            expectedIndex = namedIndex("parsed_amount", "status", "received_at"),
        )
    }

    // ==================== 夹具 ====================

    private fun indexNames(): Set<String> =
        db.query("PRAGMA index_list(`notification_records`)", arrayOf<Any?>()).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIdx))
            }
            names
        }

    /** 与 Room 生成索引名的规则一致：`index_<表名>_<列1>_<列2>...`。 */
    private fun namedIndex(vararg columns: String): String =
        "index_notification_records_" + columns.joinToString("_")

    private fun assertUsesIndex(sql: String, args: Array<Any?>, expectedIndex: String) {
        val plan = db.query("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            val detailIdx = cursor.getColumnIndex("detail")
            val details = mutableListOf<String>()
            while (cursor.moveToNext()) {
                details.add(cursor.getString(detailIdx))
            }
            details.joinToString(" | ")
        }
        assertTrue("期望走 $expectedIndex，实际计划：$plan", plan.contains(expectedIndex))
        assertTrue("不应全表扫描 notification_records，实际计划：$plan", !plan.contains("SCAN notification_records"))
    }
}
