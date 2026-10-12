package com.aibill.android.data.local

import androidx.room.Room
import com.aibill.android.data.local.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * `parse_log_id` 列的存在性 + 导出 schema 的一致性（DB v9 → v10 迁移）。
 *
 * 为什么单独守这一列：它是 AI 反馈闭环的本地锚点（跨端契约 §2），
 * 而「加了实体列但忘了写 Migration」只会在**老用户升级时**炸
 * （Room 迁移后校验 schema，AppDatabase 禁 destructive fallback）。
 * 这里用两条便宜的护栏把它钉死：
 *
 * 1. 真建库（v10）后 `PRAGMA table_info` 能查到该列 —— 验证实体声明；
 * 2. 导出的 `10.json` 里 `pending_transactions` 的 createSql 含该列 ——
 *    验证「实体 → 导出 schema」这条链（迁移 SQL 必须与之一致）。
 *
 * 残余缺口（已知，见 ARCHITECTURE §12.3）：真迁移路径（9→10 在既有库上执行）
 * 需要 MigrationTestHelper + androidTest 基建，本工程暂无。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ParseLogIdColumnTest {

    @Test
    fun `真建库后 pending_transactions 存在 parse_log_id 列`() {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val latch = CountDownLatch(1)
            var columns: List<String> = emptyList()
            db.query("PRAGMA table_info(pending_transactions)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) columns = columns + cursor.getString(nameIdx)
                latch.countDown()
            }
            assertTrue("缺 parse_log_id 列：$columns", columns.contains("parse_log_id"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `导出的 v10 schema 里含该列（实体与 schema 同步）`() {
        val schema = findSchema("10.json")
        val text = schema.readText()
        assertTrue("10.json 未导出", text.isNotBlank())
        assertTrue(
            "10.json 的 pending_transactions 缺 parse_log_id —— 实体与 schema 不同步",
            text.contains("parse_log_id"),
        )
        assertEquals(10, versionOf(text))
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
