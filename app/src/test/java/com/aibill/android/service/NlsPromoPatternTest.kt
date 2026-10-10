package com.aibill.android.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [NlsTextClassifier] 促销句式拦截。
 *
 * 背景（2026-10-09 21:04 真实日志，com.netease.yanxuan）：
 * ```
 * 排除(非账务) pkg=com.lbe.security.miui ...   ← 其它通知被正常拦
 * 包名不在白名单，走 default_rule 兜底: pkg=com.netease.yanxuan
 * ✓通过过滤: pkg=com.netease.yanxuan title=🧧双11省钱卡已开抢！
 * 调AI: ... 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉
 * →待审: amount=300 type=expense cat=购物     ← AI 把「花3得29」的 3 当成了金额
 * ```
 *
 * 58 个 `exclude_content_contains` 一个都没命中（有 🧧 但无「红包」二字、
 * 「0元兑」不是「0元领」、「省钱卡」不在表内），而 `payment_signal_regex`
 * 的裸「元」被「0元兑」命中 → 放行。
 *
 * 本测试锁的是**句式**（有限枚举的结构），不是词表——词表已为同类问题打过
 * 3 次补丁（云控 v7→v8→v9→v10），每次换个词就绕回去。
 */
class NlsPromoPatternTest {

    @Test
    @DisplayName("真实事故原文：双11省钱卡促销 → 判为促销")
    fun realWorldPromo_detected() {
        val text = "🧧双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉"
        assertTrue(NlsTextClassifier.isPromoNotification(text))
    }

    @Test
    @DisplayName("「花N得M」促销结构被拦")
    fun spendGetPattern() {
        assertTrue(NlsTextClassifier.isPromoNotification("周年庆花100得200"))
        assertTrue(NlsTextClassifier.isPromoNotification("充值花10得15"))
    }

    @Test
    @DisplayName("数字与「得」之间带单位/量词的变体也被拦（双层绕过）")
    fun spendGetWithInterjunct() {
        // kiro code review 发现的绕过：「花3元得29元」既不匹配严格句式，
        // 而 AI 把 3 当金额时「3元」自带货币单位 → 校验 5 也认它有佐证 →
        // 两层同时失效，自动入账。
        assertTrue(NlsTextClassifier.isPromoNotification("花3元得29元"))
        assertTrue(NlsTextClassifier.isPromoNotification("花3得券29"))
        assertTrue(NlsTextClassifier.isPromoNotification("双11 花10元 得 88元"))
    }

    @Test
    @DisplayName("「0元兑/领/购」零元兑换被拦")
    fun zeroYuanExchangePattern() {
        assertTrue(NlsTextClassifier.isPromoNotification("0元兑云音乐月卡"))
        assertTrue(NlsTextClassifier.isPromoNotification("0元领纸巾"))
        // 注：「一分钱领」「免费领」已由 default_rule.exclude_content_contains 覆盖，
        // 不在本层重复（词表能覆盖的就不进代码层）
    }

    @Test
    @DisplayName("「加赠N」赠品被拦")
    fun bonusPattern() {
        assertTrue(NlsTextClassifier.isPromoNotification("消费满88加赠20积分"))
    }

    // ===== 不误伤：真实交易不能被判成促销 =====
    //
    // 这一组是本文件最重要���部分。2026-10-09 自己写完促销拦截后复查发现：
    // 初版把「省钱卡」当营销词，而**省钱卡是招行/中信的真实支付方式名**，
    // 用户用省钱卡刷卡时通知里就会出现「您尾号1234的招行省钱卡支付成功」，
    // 会被无差别丢掉。服务端 verify.ts 也踩过同类坑（「首页」命中弱噪声，
    // 误杀了 `支付成功 ￥30.26 … 好想来零食乐园 完成`）。
    //
    // 所以：营销词交给服务端的 WEAK_NOISE（那里有「正向信号优先」保护），
    // 客户端只保留不会与交易用词冲突的**结构句式**。

    @Test
    @DisplayName("招行省钱卡真实支付 → 不拦（「省钱卡」是支付方式名，非营销词）")
    fun bankSavingsCardPayment_notBlocked() {
        val text = "您尾号1234的招行省钱卡支付成功，消费50.00元"
        assertFalse(
            NlsTextClassifier.isPromoNotification(text),
            "「省钱卡」是招行/中信的真实支付方式，拦它会丢真实交易",
        )
    }

    @Test
    @DisplayName("含「开抢/回本/神券」但有真实交易结构的通知 → 不拦")
    fun marketingWordsWithRealTxn_notBlocked() {
        // 营销词与交易信号共存时，客户端不预判，交给服务端「正向信号优先」判定
        assertFalse(NlsTextClassifier.isPromoNotification("您尾号5678信用卡消费88.00元，活动开抢期间享神券"))
        assertFalse(NlsTextClassifier.isPromoNotification("支付成功 20.00元，本月已回本"))
    }

    @Test
    @DisplayName("带边界的 0 元：不应误伤正常金额「3.90元」")
    fun zeroYuan_hasNumericBoundary() {
        // 无边界写 \d+元[兑送…] 会把「3.90元」的「0元」当零元兑换
        assertFalse(NlsTextClassifier.isPromoNotification("您尾号1234消费3.90元"))
        assertFalse(NlsTextClassifier.isPromoNotification("实付10.00元"))
    }

    @Test
    @DisplayName("银行真实扣款不被拦")
    fun bankTransaction_notBlocked() {
        assertFalse(NlsTextClassifier.isPromoNotification("您尾号1234的招行信用卡消费50.00元"))
    }

    @Test
    @DisplayName("支付宝交易提醒不被拦")
    fun alipayTransaction_notBlocked() {
        assertFalse(
            NlsTextClassifier.isPromoNotification("交易提醒 你有一笔20.00元的支出，点击领取9个支付宝积分")
        )
    }

    @Test
    @DisplayName("「花30元买了杯咖啡」不是促销句式")
    fun spentAtStore_notBlocked() {
        assertFalse(NlsTextClassifier.isPromoNotification("花30元在星巴克买了杯咖啡"))
    }

    @Test
    @DisplayName("「美团外卖 支付成功 ¥32.00」不被拦")
    fun normalExpense_notBlocked() {
        assertFalse(NlsTextClassifier.isPromoNotification("美团外卖 支付成功 ¥32.00"))
    }

    @Test
    @DisplayName("台球类真实交易（金额紧邻【】但无货币单位）不被拦")
    fun merchantAmount_notBlocked() {
        val text = "订单已消费 您的订单【小铁台球】39.9两小时中八（周末、节假日通用）已成功消费。"
        assertFalse(NlsTextClassifier.isPromoNotification(text))
    }

    @Test
    @DisplayName("日报汇总与促销句式互不干扰")
    fun summaryVsPromo_independent() {
        val daily = "【记账日报】昨天共有2笔支出 昨日支出220.00元，共2笔"
        assertTrue(NlsTextClassifier.isSummaryNotification(daily))
        assertFalse(NlsTextClassifier.isPromoNotification(daily))
    }

    @Test
    @DisplayName("空文本不崩")
    fun blankText_safe() {
        assertFalse(NlsTextClassifier.isPromoNotification(""))
        assertFalse(NlsTextClassifier.isSummaryNotification(""))
    }
}
