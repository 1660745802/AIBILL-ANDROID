package com.aibill.android.service

import com.aibill.android.service.NotificationRulesManager.Companion.isMarketingByWords
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 放行链路的**回归护栏**（2026-10-05）
 *
 * 背景：`nls.default_rule.exclude_content_contains` 是纯黑名单，加词很容易，
 * 但加错的代价是**误杀真实交易**——用户丢账且毫无感知，比漏拦一条广告严重得多。
 * 本测试直接读 `scripts/rules.json`（单一数据源），用两轮真机日志里的真实
 * 交易文案 + 已知广告文案做**双向**断言，任何一侧回归都会红。
 *
 * 复刻 `NotificationMonitorService.isLikelyFinancial` 的 default_rule 分支：
 *   命中营销词 且 无强交易特征 → 拒绝；否则看 payment_signal_regex → 放行
 */
class NotificationFilterRegressionTest {

    private data class Rules(
        val excludeWords: List<String>,
        val smsWords: List<String>,
        val paymentSignalRegex: String,
    )

    /**
     * 直接解析仓库里的 scripts/rules.json —— 单一数据源，改词表即改测试输入。
     * 单元测试的工作目录是 app/，所以从它往上一级找仓库根。
     */
    private val rules: Rules by lazy {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "scripts/rules.json").exists() }
            ?: error("找不到 scripts/rules.json（工作目录=${File(".").absolutePath}）")
        val moshi = Moshi.Builder().build()
        val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter<Map<String, Any>>(mapType) as JsonAdapter<Map<String, Any>>
        val root = adapter.fromJson(File(repoRoot, "scripts/rules.json").readText())!!
        val r = root.getValue("rules").let { it as Map<String, Any> }
        val nls = r.getValue("nls") as Map<String, Any>
        val defaultRule = nls.getValue("default_rule") as Map<String, Any>
        val sms = (r["smsRules"] as? Map<String, Any>).orEmpty()
        Rules(
            excludeWords = defaultRule.getValue("exclude_content_contains") as List<String>,
            smsWords = (sms["spam_keywords"] as? List<String>) ?: emptyList(),
            paymentSignalRegex = nls.getValue("payment_signal_regex") as String,
        )
    }

    /** 复刻 default_rule 分支：true = 放行去调 AI */
    private fun passes(text: String): Boolean {
        if (isMarketingByWords(text, rules.smsWords + rules.excludeWords)) return false
        return Regex(rules.paymentSignalRegex).containsMatchIn(text)
    }

    // ── 真实交易：必须放行（任何误杀都会让用户丢账）──

    @Test
    @DisplayName("真实交易文案全部放行——回归护栏，改词表时必过")
    fun `真实交易不被误杀`() {
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
    fun `广告被拦截`() {
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

    // ── 词表契约 ──

    @Test
    @DisplayName("v9 新增的 11 个电商活动词都在词表里")
    fun `v9 词表完整性`() {
        val required = listOf(
            "元购", "仅限", "限购", "抢购", "包邮",
            "满赠", "折扣", "折后", "特价", "新客价", "免单",
        )
        for (w in required) {
            assertTrue(w in rules.excludeWords, "v9 新增词缺失：$w")
        }
    }

    @Test
    @DisplayName("词表不含裸『活动』——过宽会误杀真实通知")
    fun `词表不过宽`() {
        // 「活动」曾被考虑加入，但真实通知里出现频率不低，风险大于收益
        assertFalse("活动" in rules.excludeWords, "『活动』过于宽泛，不应进排除词表")
    }

    @Test
    @DisplayName("强交易特征豁免仍然生效（营销词 + 真交易不误杀）")
    fun `强交易特征豁免`() {
        assertFalse(
            isMarketingByWords("你有一笔20.00元的支出，点击领取9个积分", listOf("领取")),
            "含强交易特征时不应判营销",
        )
        assertTrue(
            isMarketingByWords("点击领取优惠券", listOf("领取")),
            "无强交易特征时应判营销",
        )
    }
}
