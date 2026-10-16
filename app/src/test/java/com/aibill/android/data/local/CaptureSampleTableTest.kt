package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * `capture_samples` 表的**存在性** + 导出 schema 一致性（DB v10 → v11）。
 *
 * 这张表是 2026-10「查漏账」失败的直接产物：用户报「有几笔账单面板里看不到」，
 * 而 `not_payment_activity` / `no_success_keyword` / `root_mismatch` 三条 a11y 丢弃规则
 * **一个字的正文都没落过盘** → 事后结构性不可还原。本表把「被拦的内容」留在本地。
 *
 * ## 为什么必须钉
 *
 * `AppDatabase` **禁 destructive fallback**：迁移 SQL 与实体声明不一致时，
 * 只会在**老用户升级时**抛 `IllegalStateException`，而 CI 全是新库（永远走不到迁移）。
 * 所以这里钉两条：
 *
 * 1. 真建库（v11）后 `PRAGMA table_info(capture_samples)` 能查到全部列与索引；
 * 2. 导出的 `11.json` 含该表，且 `createSql` 与 `Migration(10, 11)` 的 SQL 逐字同形
 *    （列名/类型/可空性/索引名）。
 *
 * ## 残余缺口（已知）
 *
 * 真迁移路径（10→11 在既有 v10 库上执行）需要 MigrationTestHelper + androidTest 基建，
 * 本工程暂无（与 `ParseLogIdColumnTest` 同）。但**索引名**已按 Room 约定显式写死在
 * Migration 里，且下面的用例断言了导出 schema 的索引名 —— 名字漂移会在真机上炸，
 * 这里至少能在 CI 阶段看到 schema 侧的变化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CaptureSampleTableTest {

    private val expectedColumns = listOf(
        "id", "channel", "package_name", "kind", "stage", "blocked_by",
        "received_at", "text", "text_len", "content_hash", "tier",
        "redacted", "created_at",
    )

    @Test
    fun `真建库后 capture_samples 含全部列`() {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            assertEquals(expectedColumns, columnsOf(db, "capture_samples"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `真建库后两条复合索引都在`() {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val indexes = indexesOf(db, "capture_samples")
            assertTrue(
                "缺 (package_name, blocked_by) 索引：$indexes",
                indexes.contains("index_capture_samples_package_name_blocked_by"),
            )
            assertTrue("缺 received_at 索引：$indexes", indexes.contains("index_capture_samples_received_at"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `导出的 v11 schema 含该表且版本为 11`() {
        val text = findSchema("11.json").readText()
        assertNotNull(text)
        assertTrue("11.json 未导出", text.isNotBlank())
        assertTrue("11.json 缺 capture_samples —— 实体与 schema 不同步", text.contains("capture_samples"))
        assertEquals(11, versionOf(text))
    }

    @Test
    fun `导出 schema 的 createSql 与 Migration 的列定义逐字同形`() {
        val text = findSchema("11.json").readText()
        // createSql 里表名是占位符 `${TABLE_NAME}`、不含表名字面量，
        // 所以必须先定位到 capture_samples 这个实体块，再取它自己的 createSql。
        val block = text.substringAfter("\"tableName\": \"capture_samples\"")
        val createSql = Regex("\"createSql\"\\s*:\\s*\"([^\"]*?)\"")
            .find(block)?.groupValues?.get(1)
            ?: error("11.json 里找不到 capture_samples 的 createSql")

        // 列名 + 可空性：迁移 SQL 是手写的，必须与实体声明逐字一致，否则真机升级时 Room 校验失败。
        for (column in expectedColumns) {
            assertTrue(
                "createSql 缺列 $column：$createSql",
                createSql.contains("`$column` "),
            )
        }
        assertTrue("text 必须可空：$createSql", createSql.contains("`text` TEXT,"))
        assertTrue("tier 必须可空：$createSql", createSql.contains("`tier` INTEGER,"))
        for (notNull in listOf("channel", "package_name", "kind", "stage", "blocked_by",
            "received_at", "text_len", "content_hash", "redacted", "created_at")) {
            assertTrue("$notNull 必须 NOT NULL：$createSql", createSql.contains("`$notNull` TEXT NOT NULL")
                || createSql.contains("`$notNull` INTEGER NOT NULL"))
        }
    }

    private fun columnsOf(db: androidx.room.RoomDatabase, table: String): List<String> {
        val latch = CountDownLatch(1)
        var columns: List<String> = emptyList()
        db.query("PRAGMA table_info($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) columns = columns + cursor.getString(nameIdx)
            latch.countDown()
        }
        return columns
    }

    private fun indexesOf(db: androidx.room.RoomDatabase, table: String): List<String> {
        val latch = CountDownLatch(1)
        var indexes: List<String> = emptyList()
        db.query("PRAGMA index_list($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) indexes = indexes + cursor.getString(nameIdx)
            latch.countDown()
        }
        return indexes
    }

    private fun findSchema(name: String): File {
        val candidates = listOf(
            File("schemas/com.aibill.android.data.local.db.AppDatabase/$name"),
            File("app/schemas/com.aibill.android.data.local.db.AppDatabase/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到导出的 schema $name（工作目录=${File(".").absolutePath}）")
    }

    private fun versionOf(json: String): Int =
        Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("schema 里没有 version")
}
