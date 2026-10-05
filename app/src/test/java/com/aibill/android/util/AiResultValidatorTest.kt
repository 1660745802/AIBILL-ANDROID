package com.aibill.android.util

import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.entity.CategoryEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * AiResultValidator 回归测试。
 *
 * 重点锁住**金额上限 = ¥100,000**（10,000,000 分）这个值：
 * 该常量历史上被误注释成「100 万分 = 1 万元」，报错文案也写「上限 ¥10,000」，
 * 三处文档/文案都让人以为阈值是 ¥10,000。实际值必须与云控规则
 * `scripts/rules.json → rules.processor.max_amount_cents`（10000000）保持一致。
 *
 * 本测试的作用就是：以后谁再看到那句错注释想「修正」数值时，会先看到这里的断言失败。
 */
class AiResultValidatorTest {
    /** 通用原文：含货币单位，用于不涉及校验 5 的用例 */
    private val TX = "您尾号1234的招行信用卡消费50.00元"

    private val categoryDao: CategoryDao = mockk()
    private lateinit var validator: AiResultValidator

    @BeforeEach
    fun setUp() {
        // 默认「分类存在」，需要测不存在时单独覆盖
        coEvery { categoryDao.getById(any()) } returns CategoryEntity(
            id = 1,
            name = "餐饮",
            type = "expense",
            icon = "🍜",
            sortOrder = 0,
        )
        validator = AiResultValidator(categoryDao)
    }

    @Test
    fun `边界：恰好 10,000,000 分（10 万元）通过校验`() = runTest {
        val r = validator.validate(10_000_000, "expense", 1, "房租", TX)
        assertTrue(r.isValid, "10 万元应在上限内，实际 errors=${r.errors}")
    }

    @Test
    fun `边界：10,000,001 分（超过 10 万元）被拒，且文案显示正确上限`() = runTest {
        val r = validator.validate(10_000_001, "expense", 1, "房租", TX)
        assertFalse(r.isValid)
        // 文案由常量推导，不可能再出现「上限 ¥10,000」这种与实际不符的描述
        assertTrue(
            r.errors.any { it.contains("100000") },
            "错误文案应显示真实上限 100000 元，实际=${r.errors}",
        )
    }

    @Test
    fun `正常小额通过`() = runTest {
        assertTrue(validator.validate(3250, "expense", 1, "午饭", TX).isValid)
    }

    @Test
    fun `金额为 0 被拒`() = runTest {
        val r = validator.validate(0, "expense", 1, null, TX)
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("大于 0") })
    }

    @Test
    fun `负数金额被拒`() = runTest {
        assertFalse(validator.validate(-100, "expense", 1, null, TX).isValid)
    }

    @Test
    fun `非法 type 被拒`() = runTest {
        val r = validator.validate(100, "payment", 1, null, TX)
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("类型无效") })
    }

    @Test
    fun `transfer 合法 type 通过`() = runTest {
        assertTrue(validator.validate(100_000, "transfer", null, null, TX).isValid)
    }

    @Test
    fun `categoryId 不存在于本地分类表 → 被拒（防 AI 幻觉分类）`() = runTest {
        coEvery { categoryDao.getById(999) } returns null
        val r = validator.validate(100, "expense", 999, null, TX)
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("分类 ID 不存在") })
    }

    @Test
    fun `categoryId 为 null 时不查库（transfer 场景）`() = runTest {
        assertTrue(validator.validate(100_000, "transfer", null, null, TX).isValid)
    }

    @Test
    fun `描述超过 200 字符被拒`() = runTest {
        val r = validator.validate(100, "expense", 1, "x".repeat(201), TX)
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("描述过长") })
    }

    @Test
    fun `描述恰好 200 字符通过`() = runTest {
        assertTrue(validator.validate(100, "expense", 1, "x".repeat(200), TX).isValid)
    }

    @Test
    fun `多个错误同时收集（不短路）`() = runTest {
        coEvery { categoryDao.getById(999) } returns null
        val r = validator.validate(0, "bogus", 999, "y".repeat(300), TX)
        // 金额 / 类型 / 分类 / 描述 四类错误都应被记录
        assertTrue(r.errors.size >= 4, "应至少收集 4 类错误，实际=${r.errors}")
    }

    // ===== 校验 5：金额货币佐证（A1 幻觉防护）=====

    @Test
    fun `A1 事故回归：群名里的 209 被当成红包金额 → 被拒`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 真实事故原文：整段没有任何货币单位，209 来自群名「209[嗨]」
        val src = "209[嗨] [QQ红包]太越林全员老公型: [红包]国庆快乐"
        val r = validator.validate(20_900, "income", 1, "国庆红包", src)
        assertFalse(r.isValid, "原文无货币单位却通过了校验，¥209.00 会再次入库")
        assertTrue(r.errors.any { it.contains("货币单位") }, "实际 errors=${r.errors}")
    }

    @Test
    fun `校验 5 放行：原文含元`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(180, "expense", 1, "交通卡", "重庆畅通卡 刷卡完成，交易金额：1.80元").isValid)
    }

    @Test
    fun `校验 5 放行：原文含人民币`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        val src = "招商银行 您账户2415于10月04日14:42发生快捷支付扣款，人民币20.00"
        assertTrue(validator.validate(2_000, "expense", 1, "消费", src).isValid)
    }

    @Test
    fun `校验 5 放行：原文含货币符号`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(8_200, "expense", 1, "话费", "已记账 · 移动 ¥82.00 · 9月话费账单").isValid)
    }

    @Test
    fun `校验 5 忽略大小写：原文含 RMB`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(5_000, "expense", 1, "消费", "Your card was charged RMB 50.00").isValid)
    }

    @Test
    fun `校验 5 不误伤：群名带数字但正文有货币单位 → 放行`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        val src = "209[嗨] [QQ红包]你发出了 5.00元 的红包"
        assertTrue(validator.validate(500, "expense", 1, "红包", src).isValid)
    }
}
