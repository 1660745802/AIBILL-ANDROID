package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 三渠道同一套判据的**验收护栏**（架构候选 2 的直接验收点）。
 *
 * 收口前 NLS 跑 7 步预筛、SMS 只跑 2 步、A11Y 自有一套防抖：
 * 同一段促销文案从通知进来被拦，从短信进来却直接送去调 AI。本文件逐条钉住
 * 「**同样的文本，三个渠道必须得到同样的结论**」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefilterChannelParityTest {

    private val rules = PrefilterTestHarness.rules(
        paymentSignalRegex = PAYMENT_SIGNAL,
        spamKeywords = SPAM_KEYWORDS,
        defaultExcludeContent = EXCLUDE_WORDS,
    )

    private suspend fun verdictOf(capture: Capture): Verdict =
        PrefilterTestHarness.newPrefilter(rules).evaluate(capture)

    /** 同一段文本分别按三个渠道的来源形态喂进判据链（来源形态是 adapter 的职责）。 */
    private fun threeChannels(text: String, title: String, packageName: String): List<Capture> =
        SourceChannel.entries.map { channel ->
            Capture(
                channel = channel,
                packageName = packageName,
                title = title,
                fullText = text,
                receivedAt = 1_000_000L,
            )
        }

    // ── 促销句式：收口前只有 NLS 会拦 ──

    @Test
    @DisplayName("同一段促销文案，NLS / SMS / A11Y 都按 PROMO_SENTENCE 拦（收口前 SMS 与 A11Y 绕过）")
    fun `促销句式三渠道一致拦截`() = runTest {
        // 2026-10-09 真实漏记原文：58 个排除词一个没命中，只靠句式拦
        for (capture in threeChannels(PROMO_TEXT, "双11省钱卡已开抢！", "com.netease.yanxuan")) {
            val verdict = verdictOf(capture)
            assertTrue(verdict is Verdict.Block, "${capture.channel} 应拦截促销句式，实际=$verdict")
            assertEquals(BlockReason.PROMO_SENTENCE, (verdict as Verdict.Block).reason)
        }
    }

    @Test
    @DisplayName("同一段日报汇总文案，三个渠道都按 SUMMARY_REPORT 拦（收口前 SMS 与 A11Y 绕过）")
    fun `日报汇总三渠道一致拦截`() = runTest {
        val daily = "【记账日报】昨日消费支出比平日高500.77% 昨日支出1868.98元，共2笔"
        for (channel in SourceChannel.entries) {
            val verdict = verdictOf(Capture(channel, "com.example.notifier", "记账日报", daily, 1_000_000L))
            assertEquals(
                BlockReason.SUMMARY_REPORT,
                (verdict as Verdict.Block).reason,
                "${channel} 应拦日报汇总",
            )
        }
    }

    @Test
    @DisplayName("营销短信与营销通知同口径：都按 MARKETING 拦")
    fun `营销词三渠道一致拦截`() = runTest {
        val ad = "【XX银行】恭喜您获得贷款额度10万元，回复R办理"
        for (channel in SourceChannel.entries) {
            val verdict = verdictOf(Capture(channel, "com.example.notifier", "", ad, 1_000_000L))
            assertEquals(BlockReason.MARKETING, (verdict as Verdict.Block).reason, "${channel} 应拦营销文案")
        }
    }

    // ── 真实交易：统一判据不能反过来误杀 ──

    @Test
    @DisplayName("真实银行短信放行，且与通知渠道结论一致")
    fun `真实短信放行`() = runTest {
        val text = "您尾号1234储蓄卡账户10月28日支出100.00元，余额3021.55元"
        val smsVerdict = verdictOf(Capture(SourceChannel.SMS, "sms:10695588", "10695588", text, 1_000_000L))
        val nlsVerdict = verdictOf(Capture(SourceChannel.NLS, "com.example.notifier", "", text, 1_000_000L))
        assertTrue(smsVerdict is Verdict.Pass, "真实银行短信应放行，实际=$smsVerdict")
        assertTrue(nlsVerdict is Verdict.Pass, "同一文本从通知进来也应放行，实际=$nlsVerdict")
    }

    @Test
    @DisplayName("A11Y 支付页摘要放行（无障碍渠道不是被新判据误杀的那一方）")
    fun `A11Y 摘要放行`() = runTest {
        listOf(
            "com.tencent.mm" to "支付成功 ¥12.00 星巴克臻选店",
            "com.eg.android.AlipayGphone" to "支付成功 ￥35.87 盒马鲜生",
            "com.sankuai.meituan.takeoutnew" to "支付成功 ¥32.00 美团外卖",
        ).forEach { (pkg, summary) ->
            val verdict = verdictOf(Capture(SourceChannel.A11Y, pkg, "支付成功", summary, 1_000_000L))
            assertTrue(verdict is Verdict.Pass, "$pkg 的支付页摘要应放行，实际=$verdict")
        }
    }

    @Test
    @DisplayName("无支付信号的闲聊短信被拦（与通知渠道同口径）")
    fun `闲聊短信被拦`() = runTest {
        val verdict = verdictOf(Capture(SourceChannel.SMS, "sms:10086", "10086", "今天天气不错", 1_000_000L))
        assertEquals(BlockReason.NOT_FINANCIAL, (verdict as Verdict.Block).reason)
    }

    @Test
    @DisplayName("自身通知回环：三个渠道都拦自己的包名")
    fun `自身通知三渠道一致拦截`() = runTest {
        for (channel in SourceChannel.entries) {
            val verdict = verdictOf(
                Capture(channel, PrefilterTestHarness.OWN_PACKAGE, "已记账", "已记账 · ¥82.00", 1_000_000L)
            )
            assertEquals(BlockReason.SELF_NOTIFICATION, (verdict as Verdict.Block).reason)
        }
    }

    private companion object {
        const val PAYMENT_SIGNAL =
            "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"

        val SPAM_KEYWORDS = listOf("订购", "办理", "开通", "贷款", "借款", "提额", "邀请", "恭喜", "中奖")

        val EXCLUDE_WORDS = listOf("秒杀", "礼包", "可领", "待领取", "领取", "抽奖", "优惠券", "限时", "立减", "失效")

        /** 2026-10-09 双11真实漏记原文 */
        const val PROMO_TEXT = "双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本加赠99积分0元兑云音乐月卡"
    }
}
