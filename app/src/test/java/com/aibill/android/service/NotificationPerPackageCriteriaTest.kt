package com.aibill.android.service

import com.aibill.android.data.notification.PrefilterTestHarness
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.MarketingCriteria
import com.aibill.android.domain.notification.PerPackageCriteria
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **逐包判据**：某条通知「该不该放行」——微信 / 支付宝 / 其他 App / 银行 / 未配置包。
 *
 * ## 本文件是两份旧文件的收敛
 *
 * 原先 `NotificationFilterLogicTest` 与 `PerPackageRuleTest` 各测一层：
 * - `NotificationFilterLogicTest` 打**旧硬编码兜底**分支 + `default_rule` 分支；
 * - `PerPackageRuleTest` 打**云控 `nls.per_package`** 分支 + `PerPackageCriteria` 的匹配契约。
 *
 * 两者都只提供**规则数据**（词表 / 一行 per_package 配置），判定统一调生产预筛链，
 * 源码里不再有任何判据拷贝。
 *
 * ## 为什么合并后不弱化
 *
 * 两份文件确有真重复：微信支付 / 微信转账前缀 / 微信金额符号 / 微信普通聊天 / 支付宝交易提醒
 * 在两边各有一条、只是走的分支不同（per_package vs 旧硬编码兜底）。
 * 合并时把它们各并成一条，**同一条用例同时断言两条分支的结论一致**——
 * 既去掉了重复覆盖，又没有丢掉任何一条分支的护栏。
 *
 * 受保护的三条不变量的正向用例（真实交易不被误杀 / 词表不过宽 / 强交易特征豁免）在
 * [NotificationFilterRegressionTest] 与本文件的 B1 回归组，未做任何弱化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPerPackageCriteriaTest {

    private val unknownPackage = "com.example.notifier"

    // ═══════════════════════════════════════════════════════════════
    // PerPackageCriteria 匹配契约（云控一行规则怎么匹配包名）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `精确包名匹配`() {
        val rule = PerPackageCriteria(packageName = "com.tencent.mm")
        assertTrue(rule.matches("com.tencent.mm"))
        assertFalse(rule.matches("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `包名模式匹配（contains）`() {
        val rule = PerPackageCriteria(packagePattern = "bank")
        assertTrue(rule.matches("com.icbc.bank"))
        assertTrue(rule.matches("bankofchina.app"))
        assertFalse(rule.matches("com.tencent.mm"))
    }

    @Test
    fun `无匹配条件返回false`() {
        val rule = PerPackageCriteria()
        assertFalse(rule.matches("any.package"))
    }

    /** 云控 DTO 映射过来的行与领域判据是同一个形状——映射本身零逻辑。 */
    @Test
    fun `云控 PerPackageRule 映射后匹配行为不变`() {
        val dtoRule = PerPackageRule(
            packageName = "com.tencent.mm",
            passTitleExact = listOf("微信支付"),
            excludeContentContains = listOf("秒杀"),
        )
        val source = com.aibill.android.data.notification.InMemoryNotificationRuleSource(
            PrefilterTestHarness.rules(perPackage = listOf(dtoRule))
        )
        val criteria = source.snapshot().perPackage.single()
        assertTrue(criteria.matches("com.tencent.mm"))
        assertTrue(criteria.passTitleExact.contains("微信支付"))
        assertTrue(criteria.excludeContentContains.contains("秒杀"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共断言入口：per_package 配置 / 旧硬编码兜底 / default_rule
    // ═══════════════════════════════════════════════════════════════

    /** 把一条 per_package 规则装进生产判据链，返回是否放行。 */
    private suspend fun passes(rule: PerPackageRule, title: String, fullText: String): Boolean {
        val prefilter = PrefilterTestHarness.newPrefilter(
            PrefilterTestHarness.rules(perPackage = listOf(rule))
        )
        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.NLS,
                packageName = rule.packageName ?: "com.example.bank.app",
                title = title,
                fullText = fullText,
                receivedAt = 1_000_000L,
            )
        )
        return verdict is Verdict.Pass
    }

    /**
     * 旧硬编码兜底分支（per_package 与 default_rule 都为空时才会走到）。
     * 每条用例一条新的预筛：断言的是判据，不该被去重/记忆状态污染。
     */
    private suspend fun legacyJudge(packageName: String, title: String, fullText: String): Boolean = judge(
        rules = PrefilterTestHarness.rules(),
        packageName = packageName,
        title = title,
        fullText = fullText,
    )

    /** 与 rules.json nls.payment_signal_regex 一致的完整正则 */
    private val paymentSignalRegexFull =
        "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"

    /** 与 rules.json sms.spam_keywords 一致（子集，覆盖主要营销词） */
    private val smsSpamKeywords = listOf(
        "订购", "退订", "办理", "开通", "激活", "贷款", "借款",
        "提额", "申请", "审批", "邀请", "回复R", "回复TD", "免费领", "中奖", "恭喜",
    )

    /** 与 rules.json nls.default_rule.exclude_content_contains 一致（子集） */
    private val defaultExcludeContent = listOf(
        "秒杀", "礼包", "可领", "待领取", "至高", "首绑", "领取", "抽奖", "优惠券", "满减",
        "红包雨", "限时", "福利", "特惠", "立减", "补贴", "返现", "失效", "卡包",
        "即将过期", "快过期", "再不用", "来不及", "待使用", "中奖", "恭喜", "点击链接",
        "免费领", "贷款", "借款", "提额", "办理", "开通", "邀请",
    )

    private val defaultRuleRules = PrefilterTestHarness.rules(
        paymentSignalRegex = paymentSignalRegexFull,
        spamKeywords = smsSpamKeywords,
        defaultExcludeContent = defaultExcludeContent,
    )

    /** SMS 渠道（`sms:<发件号码>` 形态）过同一套判据。 */
    private suspend fun passesSmsGate(text: String): Boolean = judge(
        rules = defaultRuleRules,
        packageName = "sms:10695588",
        title = "10695588",
        fullText = text,
    )

    /** NLS 渠道（走 default_rule）过同一套判据。 */
    private suspend fun passesNlsGate(fullText: String): Boolean = judge(
        rules = defaultRuleRules,
        packageName = unknownPackage,
        title = "",
        fullText = fullText,
    )

    // ═══════════════════════════════════════════════════════════════
    // 微信：per_package 配置与旧硬编码兜底必须同结论
    // ═══════════════════════════════════════════════════════════════

    private val wechatRule = PerPackageRule(
        packageName = "com.tencent.mm",
        passTitleExact = listOf("微信支付", "微信支付凭证"),
        passTitleContains = listOf("零钱"),
        passMsgPrefix = listOf("[转账]", "[微信红包]"),
        requireAmountSymbol = true,
        excludeContentContains = listOf("秒杀", "礼包", "可领", "失效", "即将过期"),
    )

    @Test
    fun `微信支付放行（per_package 与旧硬编码兜底同结论）`() = runTest {
        assertTrue(passes(wechatRule, "微信支付", "微信支付 已支付¥24.00"))
        assertTrue(legacyJudge("com.tencent.mm", "微信支付", "微信支付 已支付¥24.00"))
    }

    @Test
    fun `微信 零钱 title contains 放行`() = runTest {
        assertTrue(legacyJudge("com.tencent.mm", "零钱通知", "零钱通知 余额变动"))
    }

    @Test
    fun `微信转账前缀放行（per_package 与旧硬编码兜底同结论）`() = runTest {
        assertTrue(passes(wechatRule, "张三", "张三 [转账]收到转账"))
        assertTrue(legacyJudge("com.tencent.mm", "张三", "张三 [转账]收到一笔转账"))
    }

    @Test
    fun `微信含金额符号放行（per_package 与旧硬编码兜底同结论）`() = runTest {
        assertTrue(passes(wechatRule, "服务号", "服务号 ¥32.00"))
        assertTrue(legacyJudge("com.tencent.mm", "服务号", "服务号 消费¥32.00"))
    }

    @Test
    fun `微信普通聊天不放行（per_package 与旧硬编码兜底同结论）`() = runTest {
        assertFalse(passes(wechatRule, "张三", "张三 明天吃饭"))
        assertFalse(legacyJudge("com.tencent.mm", "张三", "张三 今天下班一起吃饭吗"))
    }

    @Test
    fun `微信 表情消息不放行`() = runTest {
        assertFalse(legacyJudge("com.tencent.mm", "儒宝", "儒宝 [偷笑][偷笑]"))
    }

    @Test
    fun `微信 群消息不含金额不放行`() = runTest {
        assertFalse(legacyJudge("com.tencent.mm", "家人群", "家人群 明天回来吃饭"))
    }

    @Test
    fun `微信营销红包被排除`() = runTest {
        // 即使含金额符号，因命中排除词"礼包"直接拒绝
        assertFalse(passes(wechatRule, "活动", "活动 领取¥5礼包"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 支付宝
    // ═══════════════════════════════════════════════════════════════

    // 支付宝线上真实配置（与 scripts/rules.json nls.per_package 保持一致）。
    private val alipayRule = PerPackageRule(
        packageName = "com.eg.android.AlipayGphone",
        passTitleContains = listOf("交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款"),
        excludeTitleContains = listOf("卡包", "会员", "蚂蚁", "积分", "领奖", "活动"),
        excludeContentContains = listOf(
            "秒杀", "礼包", "可领", "待领取", "至高", "首绑", "领取", "抽奖", "优惠券",
            "满减", "限时", "福利", "特惠", "立减", "补贴", "返现", "失效",
            "即将过期", "快过期", "再不用", "来不及",
        ),
    )

    @Test
    fun `支付宝交易提醒放行（per_package 与旧硬编码兜底同结论）`() = runTest {
        assertTrue(passes(alipayRule, "交易提醒", "交易提醒 你有一笔30元支出"))
        assertTrue(legacyJudge("com.eg.android.AlipayGphone", "交易提醒", "交易提醒 你有一笔30元的支出"))
    }

    @Test
    fun `支付宝 花呗放行`() = runTest {
        assertTrue(legacyJudge("com.eg.android.AlipayGphone", "花呗还款提醒", "本月花呗待还"))
    }

    @Test
    fun `支付宝 蚂蚁庄园不放行`() = runTest {
        assertFalse(legacyJudge("com.eg.android.AlipayGphone", "蚂蚁庄园", "你的小鸡饿了"))
    }

    @Test
    fun `支付宝 积分活动不放行`() = runTest {
        assertFalse(legacyJudge("com.eg.android.AlipayGphone", "会员积分", "恭喜获得100积分"))
    }

    @Test
    fun `支付宝卡包营销被title排除`() = runTest {
        // 案例：支付宝卡包 你有天天秒杀13.50元红包今晚失效
        assertFalse(passes(alipayRule, "支付宝卡包", "支付宝卡包 你有天天秒杀13.50元红包今晚失效"))
    }

    @Test
    fun `支付宝失效红包被content排除`() = runTest {
        assertFalse(passes(alipayRule, "交易提醒", "交易提醒 红包即将失效"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 银行
    // ═══════════════════════════════════════════════════════════════

    private val bankRule = PerPackageRule(
        packagePattern = "bank",
        passAll = true,
        excludeContentContains = listOf("贷款", "借款", "提额", "办理"),
    )

    @Test
    fun `招商银行 包名含cmb放行`() = runTest {
        assertTrue(legacyJudge("cmb.pb", "招商银行", "信用卡消费30元"))
    }

    @Test
    fun `工商银行 包名含icbc放行`() = runTest {
        assertTrue(legacyJudge("com.icbc", "工商银行", "尾号1234消费"))
    }

    @Test
    fun `银行交易全放行`() = runTest {
        assertTrue(passes(bankRule, "招商银行", "尾号1234消费30元"))
    }

    @Test
    fun `银行贷款营销被排除`() = runTest {
        assertFalse(passes(bankRule, "招商银行", "您可申请30万贷款额度"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 其他 App 与 default_rule 分支（营销词 + payment_signal）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `其他App 含支付信号放行`() = runTest {
        assertTrue(legacyJudge("com.taobao.taobao", "订单通知", "支付成功 ¥25.00"))
    }

    @Test
    fun `其他App 无支付信号不放行`() = runTest {
        assertFalse(legacyJudge("com.taobao.taobao", "物流更新", "您的包裹已发出"))
    }

    @Test
    fun `其他App 促销消息不放行`() = runTest {
        assertFalse(legacyJudge("com.taobao.taobao", "618大促", "限时折扣快来抢购"))
    }

    @Test
    fun `未配置银行App 动账通知走 default_rule 放行`() = runTest {
        // 包名不含 bank/cmb/... 且不在 source_mapping 的农信/城商行/新银行
        assertTrue(passesNlsGate("您尾号3321的储蓄卡账户10月28日14:00支出人民币350.00元"))
    }

    @Test
    fun `未配置银行App 贷款推广被 default_rule 拦截`() = runTest {
        assertFalse(passesNlsGate("恭喜您，预审批贷款额度10万元已到账，点击链接领取"))
    }

    @Test
    fun `未配置App 无支付信号不放行`() = runTest {
        assertFalse(passesNlsGate("您的包裹已发出"))
    }

    @Test
    fun `银行短信正常放行`() = runTest {
        assertTrue(passesSmsGate("您尾号1234储蓄卡账户10月28日支出100.00元，余额3021.55元"))
    }

    @Test
    fun `营销短信被拦截`() = runTest {
        assertFalse(passesSmsGate("【XX银行】恭喜您获得贷款额度10万元，回复R办理"))
    }

    @Test
    fun `真实交易含办理字样不被误杀`() = runTest {
        // "办理"是营销词，但含尾号/入账等强交易特征 → 交 AI 判定而非过滤层直接拦（宁可多放不漏）
        assertTrue(passesNlsGate("您尾号1234信用卡办理的分期入账3500元"))
        assertTrue(passesSmsGate("您尾号1234信用卡办理的分期入账3500元"))
    }

    @Test
    fun `含强交易特征的弱营销交 AI 判定`() = runTest {
        // 营销词 + 尾号并存时不在过滤层拦（防误杀），由 AI 兜底拒识
        assertTrue(passesSmsGate("【工行】您尾号1234的信用卡可办理分期，点击链接"))
    }

    @Test
    fun `短信渠道来源显示为短信`() {
        // 真实函数（非镜像）：NotificationSourceMapping.friendlyName 的 sms: 前缀映射
        assertEquals("短信", com.aibill.android.util.NotificationSourceMapping.friendlyName("sms:10695588"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 排除优先级与 B1 回归（强交易特征豁免）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `排除词优先于放行`() = runTest {
        val rule = PerPackageRule(
            packageName = "com.example.test",
            passAll = true,
            excludeContentContains = listOf("广告"),
        )
        // passAll=true 但命中排除词 → 拒绝
        assertFalse(passes(rule, "标题", "这是广告内容"))
    }

    @Test
    fun `B1 回归：交易提醒含支出+领取 → 不应被排除词误杀`() = runTest {
        // 真实事故原文：因命中「领取」被直接拒收，导致 ¥20.00 真实交易漏记
        val real = "交易提醒 你有一笔20.00元的支出，点击领取9个支付宝积分。"
        assertTrue(
            passes(alipayRule, "交易提醒", real),
            "含强交易特征「支出」时不应被排除词一票否决",
        )
    }

    @Test
    fun `B1 回归：纯营销仍被拦截（豁免不放松整体口径）`() = runTest {
        // 无强交易特征的营销文案仍应被拒
        val ad = "领券中心 立即领取50元满减券，下单立减10元"
        assertFalse(passes(alipayRule, "领券中心", ad))
    }

    @Test
    fun `B1 回归：卡包类仍被 title 排除词拦截`() = runTest {
        assertFalse(passes(alipayRule, "卡包", "你有 3 张优惠券待使用"))
    }

    @Test
    fun `强交易特征豁免语义：命中词表且有特征 → 不判营销`() {
        val words = listOf("领取", "优惠")
        assertFalse(MarketingCriteria.isMarketingByWords("你有一笔20.00元的支出，点击领取积分", words))
        assertTrue(MarketingCriteria.isMarketingByWords("点击领取优惠券", words))
        assertFalse(MarketingCriteria.isMarketingByWords("不含任何词", words))
        assertFalse(MarketingCriteria.isMarketingByWords("含词", emptyList()))
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共断言入口：任意渠道都走同一个生产预筛
    // ═══════════════════════════════════════════════════════════════

    private suspend fun judge(
        rules: NotificationRules,
        packageName: String,
        title: String,
        fullText: String,
        channel: SourceChannel = SourceChannel.NLS,
    ): Boolean {
        val prefilter = PrefilterTestHarness.newPrefilter(rules)
        val verdict = prefilter.evaluate(
            Capture(
                channel = channel,
                packageName = packageName,
                title = title,
                fullText = fullText,
                receivedAt = 1_000_000L,
            )
        )
        return verdict is Verdict.Pass
    }
}
