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
    /**
     * 构造一段**佐证了指定金额**的原文。
     *
     * 校验 5 收紧后（AI 金额必须对得上原文里带货币单位的数字），
     * 不涉及校验 5 的用例也得自洽：原文里的金额要和传入的 amount 一致，
     * 否则它们会因为「校验 5」这个无关原因失败，掩盖真正要测的逻辑。
     */
    private fun tx(amountFen: Int): String {
        val yuan = amountFen / 100
        val fen = (amountFen % 100).toString().padStart(2, '0')
        return "您尾号1234的招行信用卡消费${yuan}.${fen}元"
    }

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
        val r = validator.validate(10_000_000, "expense", 1, "房租", tx(10_000_000))
        assertTrue(r.isValid, "10 万元应在上限内，实际 errors=${r.errors}")
    }

    @Test
    fun `边界：10,000,001 分（超过 10 万元）被拒，且文案显示正确上限`() = runTest {
        val r = validator.validate(10_000_001, "expense", 1, "房租", tx(10_000_000))
        assertFalse(r.isValid)
        // 文案由常量推导，不可能再出现「上限 ¥10,000」这种与实际不符的描述
        assertTrue(
            r.errors.any { it.contains("100000") },
            "错误文案应显示真实上限 100000 元，实际=${r.errors}",
        )
    }

    @Test
    fun `正常小额通过`() = runTest {
        assertTrue(validator.validate(3250, "expense", 1, "午饭", tx(3250)).isValid)
    }

    @Test
    fun `金额为 0 被拒`() = runTest {
        val r = validator.validate(0, "expense", 1, null, tx(3250))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("大于 0") })
    }

    @Test
    fun `负数金额被拒`() = runTest {
        assertFalse(validator.validate(-100, "expense", 1, null, tx(3250)).isValid)
    }

    @Test
    fun `非法 type 被拒`() = runTest {
        val r = validator.validate(100, "payment", 1, null, tx(100))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("类型无效") })
    }

    @Test
    fun `transfer 合法 type 通过`() = runTest {
        assertTrue(validator.validate(100_000, "transfer", null, null, tx(100_000)).isValid)
    }

    @Test
    fun `categoryId 不存在于本地分类表 → 被拒（防 AI 幻觉分类）`() = runTest {
        coEvery { categoryDao.getById(999) } returns null
        val r = validator.validate(100, "expense", 999, null, tx(100))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("分类 ID 不存在") })
    }

    @Test
    fun `categoryId 为 null 时不查库（transfer 场景）`() = runTest {
        assertTrue(validator.validate(100_000, "transfer", null, null, tx(100_000)).isValid)
    }

    @Test
    fun `描述超过 200 字符被拒`() = runTest {
        val r = validator.validate(100, "expense", 1, "x".repeat(201), tx(100))
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("描述过长") })
    }

    @Test
    fun `描述恰好 200 字符通过`() = runTest {
        assertTrue(validator.validate(100, "expense", 1, "x".repeat(200), tx(100)).isValid)
    }

    @Test
    fun `多个错误同时收集（不短路）`() = runTest {
        coEvery { categoryDao.getById(999) } returns null
        val r = validator.validate(0, "bogus", 999, "y".repeat(300), tx(3250))
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

    // ===== 校验 5 收紧：AI 金额必须对得上「带货币单位的那个数字」 =====

    @Test
    fun `2026-10-09 事故回归：双11促销句式里的「花3得29」被当金额 → 拦`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 真实原文（com.netease.yanxuan），旧判据因全文有「元」而放行
        val src = "🧧双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉"
        val r = validator.validate(300, "expense", 1, "购物", src)
        assertFalse(r.isValid, "AI 把「花3得29」的 3 当成 ¥3.00 竟然通过了校验")
        assertTrue(r.errors.any { it.contains("货币单位") }, "实际 errors=${r.errors}")
    }

    @Test
    fun `收紧后不误杀：金额紧邻【商户】但无货币单位的真实交易仍进待审`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // ai_parse_logs 实测的唯一真交易样本；旧判据下就已经进待审，收紧后行为不变
        val src = "订单已消费 您的订单【小铁台球】39.9两小时中八（周末、节假日通用）已成功消费。"
        assertFalse(validator.validate(3_990, "expense", 1, "台球", src).isValid)
    }

    @Test
    fun `收紧后不误杀：全文有货币单位但金额对得上 → 放行`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 优惠 20 元，实付 88.90 元：两个带单位数字并存，AI 给的是后者 → 应放行
        val src = "满100减20元，实付88.90元"
        assertTrue(validator.validate(8_890, "expense", 1, "消费", src).isValid)
    }

    @Test
    fun `金额带千分位也能佐证`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(123_456, "expense", 1, "大额", "转账成功 ¥1,234.56").isValid)
    }

    @Test
    fun `整数金额（无小数）也能佐证`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(2_000, "expense", 1, "话费", "充值成功 20元").isValid)
    }

    @Test
    fun `多笔带单位金额：AI 命中任意一个即可`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        val src = "您尾号1234消费39.90元，尾号5678消费128.00元"
        assertTrue(validator.validate(3_990, "expense", 1, "消费", src).isValid)
        assertTrue(validator.validate(12_800, "expense", 1, "消费", src).isValid)
    }

    @Test
    fun `无单位的裸数字不能当佐证`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 「已开抢 99 元」在另一个位置，AI 的 30 来自无单位的「30秒」
        val src = "限时30秒，已开抢 99 元包邮"
        assertFalse(validator.validate(3_000, "expense", 1, "购物", src).isValid)
    }

    // ===== 2026-10-09 kiro code review 发现的两个新回归 =====
    //
    // 首版收紧只认「数字紧邻元/¥」，把下面两类**旧判据下能正常入账**的通知
    // 静默降级成了待审。它们不是理论风险：工资到账是记账 App 的核心场景。

    @Test
    fun `万位金额不被新判据误杀：工资到账 1点2万元`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 根因：首版正则里「万」夹在数字与「元」之间，匹配不到
        assertTrue(validator.validate(1_200_000, "income", 1, "工资", "工资到账 1.2万元").isValid)
    }

    @Test
    fun `万位金额：整数万元也不被误杀`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        assertTrue(validator.validate(2_000_000, "income", 1, "转账", "微信转账 2万元").isValid)
    }

    @Test
    fun `全角数字不被新判据误杀`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 根因：\d 在 java.util.regex（未开 UNICODE_CHARACTER_CLASS）下只匹 ASCII
        assertTrue(validator.validate(5_000, "expense", 1, "消费", "消费５０．００元").isValid)
    }

    @Test
    fun `Int 回绕不得产生假佐证：43000000元 不是 50327点04元`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 回归（kiro review）：用 Int 算 yuan*100+fen 时，"43000000" 回绕成 5032704，
        // 恰好落在合法金额区间内 → 「43000000元」能为 ¥50,327.04 提供佐证 → 假放行。
        // 修复前这条会通过（isValid=true），因为佐证集里真的有 5032704。
        val r = validator.validate(5_032_704, "expense", 1, "消费", "交易金额 43000000元")
        assertFalse(r.isValid, "Int 回绕值被当成了合法佐证，这是一个假放行路径")
    }

    @Test
    fun `万位放大后超 Int 上限 → 丢弃而非回绕`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 30000000万元 = 3e14 分，远超 Int.MAX_VALUE，两层都应丢弃
        assertFalse(validator.validate(100_000, "income", 1, "奖励", "奖励金 30000000万元").isValid)
    }

    @Test
    fun `合法上限内的万位金额仍能佐证`() = runTest {
        coEvery { categoryDao.getById(1) } returns mockk()
        // 10,000,000 分 = MAX_AMOUNT_CENTS = ¥100,000 = 10万元，必须放行
        // （若因上界检查写错而误杀，这条会失败）
        assertTrue(validator.validate(10_000_000, "expense", 1, "大额", "单笔消费 10万元").isValid)
    }
}
