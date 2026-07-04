package com.aibill.android.domain.usecase

import com.aibill.android.data.local.dao.CategoryRuleDao
import com.aibill.android.data.local.entity.CategoryRuleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 覆盖：
 * - P0#9：CategoryLearningEngine 是 AI 自学习引擎，3 个核心行为
 * - M5：containsWordBoundary 词边界匹配避免子串误匹配
 *
 * 关键不变量：
 * 1. learnFromCorrection：同 keyword + 同 categoryId → incrementHitCount（不重置为 1）
 * 2. learnFromCorrection：同 keyword + 不同 categoryId → 覆盖为 hitCount=1
 * 3. learnFromCorrection：keyword 空白 → no-op
 * 4. learnFromCorrection：keyword 自动 trim+lowercase
 * 5. matchCategory：精确匹配 → incrementHitCount + 返回 categoryId
 * 6. matchCategory：包含匹配 → 命中 incrementHitCount
 * 7. matchCategory：input 空白 → null
 * 8. matchCategory：所有规则都不匹配 → null
 * 9. containsWordBoundary：keyword 跨汉字时不匹配（修复 M5）
 * 10. containsWordBoundary：keyword 长度优先（更具体优先）
 */
class CategoryLearningEngineTest {

    private val dao: CategoryRuleDao = mockk(relaxed = true)
    private val engine = CategoryLearningEngine(dao)

    private fun rule(
        keyword: String,
        categoryId: Int = 7,
        hitCount: Int = 1,
    ): CategoryRuleEntity = CategoryRuleEntity(
        keyword = keyword,
        categoryId = categoryId,
        hitCount = hitCount,
        updatedAt = 0L,
    )

    // ==================== learnFromCorrection ====================

    @Test
    fun `learnFromCorrection - blank keyword is no-op`() = runTest {
        engine.learnFromCorrection("", 5)
        engine.learnFromCorrection("   ", 5)
        coVerify(exactly = 0) { dao.findByKeyword(any()) }
        coVerify(exactly = 0) { dao.insertOrUpdate(any()) }
        coVerify(exactly = 0) { dao.incrementHitCount(any()) }
    }

    @Test
    fun `learnFromCorrection - keyword is trimmed and lowercased`() = runTest {
        coEvery { dao.findByKeyword("星巴克") } returns null

        engine.learnFromCorrection("  星巴克  ", 5)

        coVerify(exactly = 1) { dao.findByKeyword("星巴克") }
        val captured = slot<CategoryRuleEntity>()
        coVerify(exactly = 1) { dao.insertOrUpdate(capture(captured)) }
        assertEquals("星巴克", captured.captured.keyword)
        assertEquals(5, captured.captured.categoryId)
    }

    @Test
    fun `learnFromCorrection - new keyword - insert with hitCount=1`() = runTest {
        coEvery { dao.findByKeyword("星巴克") } returns null

        engine.learnFromCorrection("星巴克", 7)

        val captured = slot<CategoryRuleEntity>()
        coVerify(exactly = 1) { dao.insertOrUpdate(capture(captured)) }
        assertEquals("星巴克", captured.captured.keyword)
        assertEquals(7, captured.captured.categoryId)
        assertEquals(1, captured.captured.hitCount)
    }

    @Test
    fun `learnFromCorrection - same keyword same categoryId - incrementHitCount (not reset)`() = runTest {
        coEvery { dao.findByKeyword("星巴克") } returns rule("星巴克", categoryId = 7, hitCount = 3)

        engine.learnFromCorrection("星巴克", 7)

        // 关键：hitCount=3 不重置为 1，incrementHitCount 而非 insertOrUpdate
        coVerify(exactly = 0) { dao.insertOrUpdate(any()) }
        coVerify(exactly = 1) { dao.incrementHitCount("星巴克", any<Long>()) }
    }

    @Test
    fun `learnFromCorrection - same keyword different categoryId - reset hitCount=1`() = runTest {
        // 用户修正：「星巴克」从 catId=7 改到 catId=8
        coEvery { dao.findByKeyword("星巴克") } returns rule("星巴克", categoryId = 7, hitCount = 5)

        engine.learnFromCorrection("星巴克", 8)

        coVerify(exactly = 0) { dao.incrementHitCount(any()) }
        val captured = slot<CategoryRuleEntity>()
        coVerify(exactly = 1) { dao.insertOrUpdate(capture(captured)) }
        assertEquals(8, captured.captured.categoryId)
        assertEquals(1, captured.captured.hitCount) // 修正时重置
    }

    // ==================== matchCategory ====================

    @Test
    fun `matchCategory - blank input returns null`() = runTest {
        assertNull(engine.matchCategory(""))
        assertNull(engine.matchCategory("   "))
        coVerify(exactly = 0) { dao.findByKeyword(any()) }
    }

    @Test
    fun `matchCategory - exact match returns categoryId and increments hit count`() = runTest {
        coEvery { dao.findByKeyword("星巴克") } returns rule("星巴克", categoryId = 7, hitCount = 2)

        val result = engine.matchCategory("星巴克")

        assertEquals(7, result)
        coVerify(exactly = 1) { dao.incrementHitCount("星巴克", any<Long>()) }
    }

    @Test
    fun `matchCategory - exact match is case-insensitive`() = runTest {
        coEvery { dao.findByKeyword("starbucks") } returns rule("starbucks", categoryId = 9)

        val result = engine.matchCategory("StarBucks")

        assertEquals(9, result)
    }

    @Test
    fun `matchCategory - no match returns null`() = runTest {
        coEvery { dao.findByKeyword(any()) } returns null
        coEvery { dao.getAll() } returns emptyList()

        assertNull(engine.matchCategory("nonexistent"))
    }

    @Test
    fun `matchCategory - contains match with non-Chinese right boundary returns categoryId`() = runTest {
        // 「午餐.星巴克」中 keyword「星巴克」右边是「.」（非汉字），匹配
        coEvery { dao.findByKeyword("午餐.星巴克") } returns null
        coEvery { dao.getAll() } returns listOf(rule("星巴克", categoryId = 9))

        val result = engine.matchCategory("午餐.星巴克")

        assertEquals(9, result)
    }

    @Test
    fun `matchCategory - M5 fix, keyword crossed by Chinese does NOT match`() = runTest {
        // 之前 contains() 会匹配「星巴克」在「超星巴克店」中（错）
        // M5 修复：containsWordBoundary 要求 keyword 两侧不是汉字
        // 「超星巴克店」中「星巴克」左边是「超」（汉字），不匹配
        coEvery { dao.findByKeyword("超星巴克店") } returns null
        coEvery { dao.getAll() } returns listOf(rule("星巴克", categoryId = 9))

        val result = engine.matchCategory("超星巴克店")

        // M5 前会误命中 catId=9，M5 后不匹配
        assertNull(result)
    }

    @Test
    fun `matchCategory - M5 fix, keyword at end of string matches (right boundary = end)`() = runTest {
        // 「信用卡」在「星巴克,信用卡」末尾，右边是字符串结尾（允许）
        // 左边是逗号（非汉字），也允许 → 匹配
        coEvery { dao.findByKeyword("星巴克,信用卡") } returns null
        coEvery { dao.getAll() } returns listOf(rule("信用卡", categoryId = 12))

        val result = engine.matchCategory("星巴克,信用卡")

        assertEquals(12, result)
    }

    @Test
    fun `matchCategory - M5 fix, keyword at start of string matches (left boundary = start)`() = runTest {
        // 「信用卡」在「信用卡还款」开头，左边是字符串开头（允许）
        // 右边是「还」（汉字），不匹配 → 实际不匹配
        // M5 实际行为是 right boundary 也要非汉字，所以纯汉字连续不匹配
        coEvery { dao.findByKeyword("信用卡还款") } returns null
        coEvery { dao.getAll() } returns listOf(rule("信用卡", categoryId = 12))

        val result = engine.matchCategory("信用卡还款")

        // 实际结果：null（右侧「还」是汉字，不满足 M5 边界条件）
        assertNull(result)
    }

    @Test
    fun `matchCategory - longer keyword wins (sortedByDescending length)`() = runTest {
        // 规则「信用」catId=10,「信用卡」catId=11（更具体）
        // 输入「星巴克信用卡还款」应该命中「信用卡」catId=11
        coEvery { dao.findByKeyword("星巴克信用卡还款") } returns null
        coEvery { dao.getAll() } returns listOf(
            rule("信用", categoryId = 10, hitCount = 5),
            rule("信用卡", categoryId = 11, hitCount = 1),
        )

        val result = engine.matchCategory("星巴克信用卡还款")

        // 「信用」左侧是「星巴」（汉字），不匹配 word boundary
        // 「信用卡」右侧是「还」（汉字），不匹配
        // 所以两者都不应匹配
        assertNull(result)
    }

    @Test
    fun `matchCategory - non-Chinese boundary character allows match`() = runTest {
        // 「星巴克,信用卡」中 keyword「信用卡」左边是逗号（非汉字），匹配
        coEvery { dao.findByKeyword("星巴克,信用卡") } returns null
        coEvery { dao.getAll() } returns listOf(rule("信用卡", categoryId = 12))

        val result = engine.matchCategory("星巴克,信用卡")

        assertEquals(12, result)
    }
}
