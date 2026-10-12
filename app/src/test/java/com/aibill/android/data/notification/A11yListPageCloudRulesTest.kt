package com.aibill.android.data.notification

import com.aibill.android.data.remote.dto.response.A11yRulesDto
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.service.NotificationRules
import com.aibill.android.service.NotificationRulesMapping
import com.aibill.android.service.PaymentAccessibilityRecognition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `a11y.list_page_*` 的「云控优先、内置兜底」接线。
 *
 * 服务端已把默认值写进内容真源，但客户端仍须对**旧载荷（缺键）/坏值（空数组、非正阈值）**
 * 回退内置默认——阈值 0 会把任何带列表词的页面判成列表页（拦一切），是灾难级误杀。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class A11yListPageCloudRulesTest {

    /** 命中内置列表词「账单」+ 4 个金额。 */
    private val billPage = "账单明细 交易成功 ¥12.00 咖啡 交易成功 ¥32.50 午餐 " +
        "交易成功 ¥8.00 早餐 交易成功 ¥100.00 转账"

    /** 只有「交易记录」命中、无「账单」：用于验证云控把词表收窄后确实生效。 */
    private val recordPage = "交易记录 ¥12.00 咖啡 ¥32.50 午餐 ¥8.00 早餐 ¥100.00 转账"

    /** 只有 2 个金额但带列表词：用于验证阈值确实读的是云控值。 */
    private val twoAmountBillPage = "账单 ¥12.00 咖啡 ¥32.50 午餐"

    /**
     * 真支付结果页：3 个金额（实付/原价/优惠）+ 底部「查看账单」入口。
     * 「账单」只是入口按钮的一部分、位于页面底部，不构成列表页。
     */
    private val resultPageWithBillEntry =
        "支付成功 实付 ¥32.50 原价 ¥45.00 优惠 ¥12.50 查看账单"

    private fun capture(pageText: String) = Capture(
        channel = SourceChannel.A11Y,
        packageName = "com.tencent.mm",
        title = "支付成功",
        fullText = "支付成功 ¥12.00 星巴克臻选店",
        receivedAt = 1_000_000L,
        pageText = pageText,
    )

    private fun a11yDto(keywords: List<String>?, minAmounts: Int?) = A11yRulesDto(
        embeddedPaymentApps = listOf("com.tencent.mm"),
        successKeywords = listOf("支付成功"),
        embeddedSuccessKeywords = emptyList(),
        commonExcludeKeywords = emptyList(),
        wechatAlipayExcludeKeywords = emptyList(),
        amountRegex = """[¥￥][ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*(\d+\.?\d{0,2})""",
        cooldownMinutes = 5,
        listPageKeywords = keywords,
        listPageMinAmounts = minAmounts,
    )

    private fun dto(a11y: A11yRulesDto) = NotificationRulesDto(
        nls = null,
        a11y = a11y,
        smsRules = null,
        sourceMapping = null,
        processor = null,
        precheck = null,
    )

    private fun prefilter(rules: NotificationRules): NotificationPrefilter =
        PrefilterTestHarness.newPrefilter(rules)

    /** 在出厂规则基础上覆写列表页参数（避免给通用脚手架再加参数）。 */
    private fun rulesWithListPage(
        keywords: List<String> = PaymentAccessibilityRecognition.DEFAULT_LIST_KEYWORDS,
        minAmounts: Int = PaymentAccessibilityRecognition.LIST_PAGE_MIN_AMOUNTS,
    ): NotificationRules {
        val base = PrefilterTestHarness.rules()
        return base.copy(
            a11y = base.a11y.copy(listPageKeywords = keywords, listPageMinAmounts = minAmounts),
        )
    }

    @Test
    @DisplayName("云控给了值就用云控值：关键词只给 [账单] 时，交易记录页不再被拦")
    fun cloudKeywordsTakePrecedence() = runTest {
        val rules = rulesWithListPage(keywords = listOf("账单"), minAmounts = 3)
        val prefilter = prefilter(rules)

        // 云控收窄了词表：只有「账单」命中 → 交易记录页放行（旧内置词表会拦）
        assertTrue(prefilter.evaluate(capture(recordPage)) is Verdict.Pass)
        assertEquals(
            BlockReason.LIST_PAGE,
            (prefilter.evaluate(capture(billPage)) as Verdict.Block).reason,
        )
    }

    @Test
    @DisplayName("云控阈值生效：min=2 时 2 个金额+账单也拦；缺省 min=3 时同样文本放行")
    fun cloudMinAmountsTakePrecedence() = runTest {
        val cloud = prefilter(rulesWithListPage(minAmounts = 2))
        assertEquals(
            BlockReason.LIST_PAGE,
            (cloud.evaluate(capture(twoAmountBillPage)) as Verdict.Block).reason,
        )

        val builtin = prefilter(rulesWithListPage())
        assertTrue(builtin.evaluate(capture(twoAmountBillPage)) is Verdict.Pass)
    }

    @Test
    @DisplayName("云控缺这两个键（旧载荷）→ 映射回退内置默认，行为与现在逐字一致")
    fun missingKeysFallBackToBuiltin() = runTest {
        val rules = NotificationRulesMapping.fromDto(dto(a11yDto(keywords = null, minAmounts = null))) {
            PrefilterTestHarness.rules()
        }
        assertEquals(PaymentAccessibilityRecognition.DEFAULT_LIST_KEYWORDS, rules.a11y.listPageKeywords)
        assertEquals(PaymentAccessibilityRecognition.LIST_PAGE_MIN_AMOUNTS, rules.a11y.listPageMinAmounts)

        assertEquals(
            BlockReason.LIST_PAGE,
            (prefilter(rules).evaluate(capture(recordPage)) as Verdict.Block).reason,
        )
    }

    @Test
    @DisplayName("云控给了坏值（空数组 / 0 / 负数）→ 回退内置默认，绝不「阈值 0 拦一切」")
    fun badValuesFallBackToBuiltin() = runTest {
        // 空关键词列表 + 0 阈值：若被当真则「命中空词表 + 金额≥0」会拦掉一切
        val emptyAndZero = prefilter(
            rulesWithListPage(keywords = emptyList(), minAmounts = 0),
        )
        assertEquals(
            BlockReason.LIST_PAGE,
            (emptyAndZero.evaluate(capture(billPage)) as Verdict.Block).reason,
        )
        // 结果页（无列表词）仍必须放行——证明不是「拦一切」
        assertTrue(emptyAndZero.evaluate(capture("支付成功 ¥32.50 星巴克")) is Verdict.Pass)

        // 负阈值同样回退
        val negative = prefilter(rulesWithListPage(minAmounts = -5))
        assertEquals(
            BlockReason.LIST_PAGE,
            (negative.evaluate(capture(billPage)) as Verdict.Block).reason,
        )
    }

    @Test
    @DisplayName("反例护栏：真结果页含 3 个金额 +「查看账单」入口 → 必须放行（不得误杀真交易）")
    fun realResultPageWithBillEntryIsNotListPage() = runTest {
        // 服务端提的 P3 反例：3 个金额 + 列表词「账单」同时出现。
        // 若只要「金额≥3 且命中列表词」就拦，真支付结果页会被当历史账单拦掉 = P0 误杀漏账。
        val prefilter = prefilter(rulesWithListPage())
        assertTrue(
            prefilter.evaluate(capture(resultPageWithBillEntry)) is Verdict.Pass,
            "真支付结果页被当列表页拦掉了（P0 误杀）",
        )
        // 正例仍在：词表命中且出现在标题区的真列表页依旧被拦
        assertEquals(
            BlockReason.LIST_PAGE,
            (prefilter.evaluate(capture(billPage)) as Verdict.Block).reason,
        )
    }

    @Test
    @DisplayName("云控值经映射进入规则模型（不只走单测脚手架）")
    fun mappingPicksCloudValues() {
        val rules = NotificationRulesMapping.fromDto(dto(a11yDto(listOf("账单"), 2))) {
            PrefilterTestHarness.rules()
        }
        assertEquals(listOf("账单"), rules.a11y.listPageKeywords)
        assertEquals(2, rules.a11y.listPageMinAmounts)
    }
}
