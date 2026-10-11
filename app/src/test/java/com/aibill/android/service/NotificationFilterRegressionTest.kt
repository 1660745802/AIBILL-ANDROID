package com.aibill.android.service

import com.aibill.android.data.notification.InMemoryNotificationRuleSource
import com.aibill.android.data.notification.PrefilterTestHarness
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.MarketingCriteria
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationTextClassifier
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.squareup.moshi.Moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 放行链路的**回归护栏**（2026-10-05 建，2026-10-10 改为直接打生产判据）。
 *
 * 背景：`nls.default_rule.exclude_content_contains` 是纯黑名单，加词很容易，
 * 但加错的代价是**误杀真实交易**——用户丢账且毫无感知，比漏拦一条广告严重得多。
 * 本测试直接读 `scripts/rules.json`（单一数据源），用两轮真机日志里的真实
 * 交易文案 + 已知广告文案做**双向**断言，任何一侧回归都会红。
 *
 * ## 本文件同时吸收了促销句式的单元护栏
 *
 * 原 `NlsPromoPatternTest` 测的是同一条不变量的另一面：**促销句式拦截及其反向误杀防护**。
 * 收口后并入本文件——促销句式与真机文案回归共用同一份 `scripts/rules.json` 语境，
 * 不再各自维护一份 fixture。
 *
 * ## 护栏护的是实现，不是拷贝
 *
 * 收口前这里的 `passes()` 是「复刻 `NotificationMonitorService.isLikelyFinancial` 的
 * default_rule 分支」写的一份实现。现在它做的是：
 * `scripts/rules.json` →（生产 DTO + **生产映射** [NotificationRulesMapping]）
 * → `InMemoryNotificationRuleSource` → **生产预筛链**。
 * 也就是说：改词表、改分支顺序、改判据语义，三处都会让这里变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationFilterRegressionTest {

    /**
     * 直接解析仓库里的 scripts/rules.json —— 单一数据源，改词表即改测试输入。
     * 单元测试的工作目录是 app/，所以从它往上一级找仓库根。
     *
     * 解析用生产的 `NotificationRulesData` DTO，映射用生产的
     * [NotificationRulesMapping]：测试侧不再持有任何「云控字段名 ↔ 判据字段名」的翻译。
     */
    private val rulesJson: String by lazy {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "scripts/rules.json").exists() }
            ?: error("找不到 scripts/rules.json（工作目录=${File(".").absolutePath}）")
        File(repoRoot, "scripts/rules.json").readText()
    }

    /** 走生产映射得到的规则模型（in-memory 源喂的就是它）。 */
    private val productionRules: NotificationRules by lazy {
        val data = Moshi.Builder().build()
            .adapter(NotificationRulesData::class.java)
            .fromJson(rulesJson)
            ?: error("scripts/rules.json 解析失败")
        val dto = data.rules ?: error("scripts/rules.json 缺 rules 段")
        NotificationRulesMapping.fromDto(dto) { error("规则字段齐全，不应回退兜底") }
    }

    private val prefilter: NotificationPrefilter by lazy {
        PrefilterTestHarness.newPrefilter(productionRules)
    }

    private fun excludedWords(): List<String> =
        productionRules.nls.defaultExcludeContent + productionRules.sms.spamKeywords

    /** 生产判据的结论：true = 放行去调 AI。 */
    private suspend fun passes(text: String): Boolean {
        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.NLS,
                packageName = "com.example.notifier",
                title = "",
                fullText = text,
                receivedAt = 1_000_000L,
            )
        )
        return verdict is Verdict.Pass
    }

    // ── 真实交易：必须放行（任何误杀都会让用户丢账）──

    @Test
    @DisplayName("真实交易文案全部放行——回归护栏，改词表时必过")
    fun `真实交易不被误杀`() = runTest {
        val real = listOf(
            "重庆畅通卡 刷卡完成，交易金额：1.80元",
            "招商银行 您账户2415于10月04日14:42在【支付宝-重庆驿满新能源科技有限公司】发生快捷支付扣款，人民币20.00",
            "招商银行 信用卡通知：您尾号1678的招行信用卡消费35.87人民币。",
            "您在支付宝记账成功35.87元 今日消费1次，共支出35.87元",
            "话费账单提醒 尊敬的136****9969客户您好，您2026年09月话费账单已更新，本期消费总额82.00元。点击立即查看详情>>",
            "已记账 · com.greenpoint.android.mc10086.activity ¥82.00 · 9月话费账单",
            "交易提醒 你有一笔20.00元的支出，点击领取9个支付宝积分。",
            "[QQ红包]太越林全员老公型: [红包]国庆快乐",
        )
        for (t in real) {
            assertTrue(passes(t), "真实交易被误杀：$t")
        }
    }

    // ── 广告：必须拦截 ──

    @Test
    @DisplayName("广告文案全部拦截——含 v9 新增的电商活动词")
    fun `广告被拦截`() = runTest {
        val ads = listOf(
            "0.01元购的活动 一人仅限一单",                       // v9 修复的真实误判
            "😍1号会员日来啦 🛒12包抽纸0元领千份放量！🔥实付159元再享40元红包返利>",
            "【短的发布会】鼓掌狂魔李健化身价格屠夫，荣耀Magic9系列3999元起售？！",
            "支付宝卡包 爆红包等4张优惠券未使用，请关注券过期时间",
            "🎉举国同庆，游园会献礼！ 精选表盘免费体验72小时，立即领取>>>",
            "恭喜！ 你收到了一份专属推荐【米家制冰机】",
            "淘宝有新版本啦～ 官方立减：爆款商品8.5折起，叠券更便宜",
        )
        for (t in ads) {
            assertFalse(passes(t), "广告穿透：$t")
        }
    }

    // ── 词表契约（数据侧护栏：词表本身有没有按 v9/v10 的约定加词）──

    @Test
    @DisplayName("v9 新增的 11 个电商活动词都在词表里")
    fun `v9 词表完整性`() {
        val required = listOf(
            "元购", "仅限", "限购", "抢购", "包邮",
            "满赠", "折扣", "折后", "特价", "新客价", "免单",
        )
        for (w in required) {
            assertTrue(w in excludedWords(), "v9 新增词缺失：$w")
        }
    }

    @Test
    @DisplayName("词表不含裸『活动』——过宽会误杀真实通知")
    fun `词表不过宽`() {
        // 「活动」曾被考虑加入，但真实通知里出现频率不低，风险大于收益
        assertFalse("活动" in excludedWords(), "『活动』过于宽泛，不应进排除词表")
    }

    @Test
    @DisplayName("强交易特征豁免仍然生效（营销词 + 真交易不误杀）")
    fun `强交易特征豁免`() {
        assertFalse(
            MarketingCriteria.isMarketingByWords("你有一笔20.00元的支出，点击领取9个积分", listOf("领取")),
            "含强交易特征时不应判营销",
        )
        assertTrue(
            MarketingCriteria.isMarketingByWords("点击领取优惠券", listOf("领取")),
            "无强交易特征时应判营销",
        )
    }

    // ── v10：汇总/日报通知（代码层判定，因含「支出」无法用词表拦）──

    @Test
    @DisplayName("记账日报被识别为汇总通知")
    fun `记账日报是汇总通知`() {
        // 实测这两条被误记成单笔支出
        assertTrue(NotificationTextClassifier.isSummaryNotification("【记账日报】昨天共有1笔支出 昨日支出28.90元，共1笔"))
        assertTrue(
            NotificationTextClassifier.isSummaryNotification(
                "【记账日报】昨日消费支出比平日高500.77% 昨日支出1868.98元，共2笔"
            )
        )
    }

    @Test
    @DisplayName("v10 8 个广告词都在词表里")
    fun `v10 词表完整性`() {
        val required = listOf(
            "芭芭农场", "一分钱领", "红包已就位", "红包正在向你招手",
            "话费红包", "名额有限", "燃油费", "膨胀券",
        )
        for (w in required) {
            assertTrue(w in excludedWords(), "v10 新增词缺失：$w")
        }
    }

    @Test
    @DisplayName("含『共支出N元』的真实账单通知不能被当汇总")
    fun `真实账单通知不误判为汇总`() = runTest {
        // 量词是「次」「元」而非「笔」——这是与日报的关键区别
        val real = listOf(
            "您在美团记账成功93.00元 今日消费1次，共支出93.00元",
            "您在支付宝记账成功10.00元 今日消费2次，共支出45.87元",
            "您在支付宝记账成功600.00元 今日消费2次，共支出616.80元",
        )
        for (t in real) {
            assertFalse(NotificationTextClassifier.isSummaryNotification(t), "真实账单通知被误判为汇总：$t")
            assertTrue(passes(t), "真实账单通知被误杀：$t")
        }
    }

    // ── 收口后的新护栏：判据链不碰云控基础设施 ──

    @Test
    @DisplayName("整条判据链只依赖注入的规则源——不读 SP、不发网络")
    fun `判据链只依赖注入的规则源`() {
        // 规则源是 InMemory 版本：这条用例能跑通本身就是「判据链不碰
        // SharedPreferences / Retrofit / assets」的证明（生产源才会去碰它们）。
        val source = InMemoryNotificationRuleSource(productionRules, generation = 7)
        assertEquals(7, source.snapshot().generation)
        assertTrue(source.snapshot().paymentSignal.containsMatchIn("支出10.00元"))
        assertTrue(source.snapshot().isMarketing("恭喜您获得优惠券"))
        assertFalse(source.snapshot().isMarketing("尾号1234支出10.00元"))
    }

    @Test
    @DisplayName("日报与促销文案在生产判据链里按 SUMMARY_REPORT / PROMO_SENTENCE 拦")
    fun `代码层判据在生产链上生效`() = runTest {
        assertEquals(
            BlockReason.SUMMARY_REPORT,
            blockReasonOf("【记账日报】昨日消费支出比平日高500.77% 昨日支出1868.98元，共2笔"),
        )
        assertEquals(
            BlockReason.PROMO_SENTENCE,
            blockReasonOf("双11省钱卡花3得29，一笔回本加赠99积分0元兑云音乐月卡"),
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 促销句式（原 NlsPromoPatternTest 并入）：结构拦截 + 反向误杀防护
    //
    // 锁的是**句式**（有限枚举的结构），不是词表——词表已为同类问题打过三次补丁
    // （云控 v7→v8→v9→v10），每次换个词就绕回去。
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真实事故原文：双11省钱卡促销 → 判为促销")
    fun realWorldPromo_detected() {
        val text = "🧧双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉"
        assertTrue(NotificationTextClassifier.isPromoNotification(text))
    }

    @Test
    @DisplayName("「花N得M」促销结构被拦")
    fun spendGetPattern() {
        assertTrue(NotificationTextClassifier.isPromoNotification("周年庆花100得200"))
        assertTrue(NotificationTextClassifier.isPromoNotification("充值花10得15"))
    }

    @Test
    @DisplayName("数字与「得」之间带单位/量词的变体也被拦（双层绕过）")
    fun spendGetWithInterjunct() {
        // kiro code review 发现的绕过：「花3元得29元」既不匹配严格句式，
        // 而 AI 把 3 当金额时「3元」自带货币单位 → 校验 5 也认它有佐证 →
        // 两层同时失效，自动入账。
        assertTrue(NotificationTextClassifier.isPromoNotification("花3元得29元"))
        assertTrue(NotificationTextClassifier.isPromoNotification("花3得券29"))
        assertTrue(NotificationTextClassifier.isPromoNotification("双11 花10元 得 88元"))
    }

    @Test
    @DisplayName("「0元兑/领/购」零元兑换被拦")
    fun zeroYuanExchangePattern() {
        assertTrue(NotificationTextClassifier.isPromoNotification("0元兑云音乐月卡"))
        assertTrue(NotificationTextClassifier.isPromoNotification("0元领纸巾"))
        // 注：「一分钱领」「免费领」已由 default_rule.exclude_content_contains 覆盖，
        // 不在本层重复（词表能覆盖的就不进代码层）
    }

    @Test
    @DisplayName("「加赠N」赠品被拦")
    fun bonusPattern() {
        assertTrue(NotificationTextClassifier.isPromoNotification("消费满88加赠20积分"))
    }

    // ── 不误伤：真实交易不能被判成促销 ──
    //
    // 「省钱卡」是招行/中信的真实支付方式名，用户刷卡时通知里就会出现
    // 「您尾号1234的招行省钱卡支付成功」，无差别拦截会直接丢真实交易。

    @Test
    @DisplayName("招行省钱卡真实支付 → 不拦（「省钱卡」是支付方式名，非营销词）")
    fun bankSavingsCardPayment_notBlocked() {
        val text = "您尾号1234的招行省钱卡支付成功，消费50.00元"
        assertFalse(
            NotificationTextClassifier.isPromoNotification(text),
            "「省钱卡」是招行/中信的真实支付方式，拦它会丢真实交易",
        )
    }

    @Test
    @DisplayName("含「开抢/回本/神券」但有真实交易结构的通知 → 不拦")
    fun marketingWordsWithRealTxn_notBlocked() {
        // 营销词与交易信号共存时，客户端不预判，交给服务端「正向信号优先」判定
        assertFalse(NotificationTextClassifier.isPromoNotification("您尾号5678信用卡消费88.00元，活动开抢期间享神券"))
        assertFalse(NotificationTextClassifier.isPromoNotification("支付成功 20.00元，本月已回本"))
    }

    @Test
    @DisplayName("带边界的 0 元：不应误伤正常金额「3.90元」")
    fun zeroYuan_hasNumericBoundary() {
        // 无边界写 \d+元[兑送…] 会把「3.90元」的「0元」当零元兑换
        assertFalse(NotificationTextClassifier.isPromoNotification("您尾号1234消费3.90元"))
        assertFalse(NotificationTextClassifier.isPromoNotification("实付10.00元"))
    }

    @Test
    @DisplayName("银行真实扣款不被拦")
    fun bankTransaction_notBlocked() {
        assertFalse(NotificationTextClassifier.isPromoNotification("您尾号1234的招行信用卡消费50.00元"))
    }

    // 原 `alipayTransaction_notBlocked`（"交易提醒 你有一笔20.00元的支出，点击领取9个支付宝积分"）
    // 已删除：该文本已在 `真实交易不被误杀` 里过生产判据链并断言放行——判据链放行 implies
    // 促销句式未命中（促销是链上的早退分支），覆盖等价。

    @Test
    @DisplayName("「花30元买了杯咖啡」不是促销句式")
    fun spentAtStore_notBlocked() {
        assertFalse(NotificationTextClassifier.isPromoNotification("花30元在星巴克买了杯咖啡"))
    }

    @Test
    @DisplayName("「美团外卖 支付成功 ¥32.00」不被拦")
    fun normalExpense_notBlocked() {
        assertFalse(NotificationTextClassifier.isPromoNotification("美团外卖 支付成功 ¥32.00"))
    }

    @Test
    @DisplayName("台球类真实交易（金额紧邻【】但无货币单位）不被拦")
    fun merchantAmount_notBlocked() {
        val text = "订单已消费 您的订单【小铁台球】39.9两小时中八（周末、节假日通用）已成功消费。"
        assertFalse(NotificationTextClassifier.isPromoNotification(text))
    }

    @Test
    @DisplayName("日报汇总与促销句式互不干扰")
    fun summaryVsPromo_independent() {
        val daily = "【记账日报】昨天共有2笔支出 昨日支出220.00元，共2笔"
        assertTrue(NotificationTextClassifier.isSummaryNotification(daily))
        assertFalse(NotificationTextClassifier.isPromoNotification(daily))
    }

    @Test
    @DisplayName("空文本不崩")
    fun blankText_safe() {
        assertFalse(NotificationTextClassifier.isPromoNotification(""))
        assertFalse(NotificationTextClassifier.isSummaryNotification(""))
    }

    private suspend fun blockReasonOf(text: String): BlockReason {
        val verdict = prefilter.evaluate(
            Capture(
                channel = SourceChannel.NLS,
                packageName = "com.example.notifier",
                title = "",
                fullText = text,
                receivedAt = 1_000_000L,
            )
        )
        assertTrue(verdict is Verdict.Block, "应当被拦截：$text")
        return (verdict as Verdict.Block).reason
    }
}
