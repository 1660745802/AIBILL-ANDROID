package com.aibill.android.data.notification

import com.aibill.android.data.remote.dto.response.A11yAppProfileDto
import com.aibill.android.data.remote.dto.response.A11yRulesDto
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.domain.notification.A11yProfileOverride
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.data.notification.rules.NotificationRulesMapping
import com.aibill.android.util.AppLogger
import com.squareup.moshi.Moshi
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A11Y 摘取 profile 的云控接线（服务端 `docs/API.md` 附录 C §10）：
 * Moshi 解析 → 映射 → 快照（逐键回退 + schema 守卫 + 回退告警）→ 预筛按包消费。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class A11yProfileCloudRulesTest {

    private val moshi: Moshi = Moshi.Builder().build()

    private fun a11yDto(
        merchantLabels: List<String>? = null,
        profilesSchema: Int? = null,
        appProfiles: Map<String, A11yAppProfileDto>? = null,
    ) = A11yRulesDto(
        embeddedPaymentApps = listOf("com.tencent.mm"),
        successKeywords = listOf("支付成功"),
        embeddedSuccessKeywords = emptyList(),
        commonExcludeKeywords = listOf("购物车"),
        wechatAlipayExcludeKeywords = listOf("朋友圈"),
        amountRegex = """[¥￥](\d+\.?\d{0,2})""",
        cooldownMinutes = 5,
        merchantLabels = merchantLabels,
        profilesSchema = profilesSchema,
        appProfiles = appProfiles,
    )

    private fun dto(a11y: A11yRulesDto) = NotificationRulesDto(
        nls = null, a11y = a11y, smsRules = null, sourceMapping = null, processor = null, precheck = null,
    )

    // ── Moshi ──

    @Test
    @DisplayName("Moshi 解析 app_profiles / merchant_labels / profiles_schema，并忽略 P2 的 viewId 未知键")
    fun moshiReadsProfileFields() {
        val json = """
            {"embedded_payment_apps":["com.tencent.mm"],"success_keywords":["支付成功"],
             "embedded_success_keywords":[],"common_exclude_keywords":[],"wechat_alipay_exclude_keywords":[],
             "amount_regex":"[¥￥](\\d+)","cooldown_minutes":5,
             "merchant_labels":["收款方","店铺"],
             "profiles_schema":1,
             "app_profiles":{"com.tencent.mm":{
                "success_keywords":["已付款"],"exclude_keywords":["充值页"],
                "list_page_head_chars":40,
                "amount_view_id":"pay_amount","merchant_view_id":"merchant_name"}}}
        """.trimIndent()
        val dto = moshi.adapter(A11yRulesDto::class.java).fromJson(json)!!

        assertEquals(listOf("收款方", "店铺"), dto.merchantLabels)
        assertEquals(1, dto.profilesSchema)
        val profile = dto.appProfiles!!.getValue("com.tencent.mm")
        assertEquals(listOf("已付款"), profile.successKeywords)
        assertEquals(listOf("充值页"), profile.excludeKeywords)
        assertEquals(40, profile.listPageHeadChars)
    }

    @Test
    @DisplayName("旧载荷缺新键 → 全为 null（不 failOnUnknown，行为退回全局）")
    fun legacyPayloadHasNullNewKeys() {
        val json = """{"embedded_payment_apps":[],"success_keywords":[],"embedded_success_keywords":[],
                       "common_exclude_keywords":[],"wechat_alipay_exclude_keywords":[],"amount_regex":"x","cooldown_minutes":5}"""
        val dto = moshi.adapter(A11yRulesDto::class.java).fromJson(json)!!
        assertNull(dto.merchantLabels)
        assertNull(dto.profilesSchema)
        assertNull(dto.appProfiles)
    }

    // ── 映射 ──

    @Test
    @DisplayName("映射：merchant_labels / profiles_schema / app_profiles → 规则模型（缺省用内置 9 标签）")
    fun mappingCarriesProfileFields() {
        val rules = NotificationRulesMapping.fromDto(
            dto(
                a11yDto(
                    merchantLabels = listOf("店铺"),
                    profilesSchema = 1,
                    appProfiles = mapOf("com.tencent.mm" to A11yAppProfileDto(successKeywords = listOf("已付款"))),
                )
            )
        ) { PrefilterTestHarness.rules() }
        assertEquals(listOf("店铺"), rules.a11y.merchantLabels)
        assertEquals(1, rules.a11y.profilesSchema)
        assertEquals(listOf("已付款"), rules.a11y.appProfiles.getValue("com.tencent.mm").successKeywords)

        // 缺省：merchant_labels 回退内置 9 标签
        val legacy = NotificationRulesMapping.fromDto(dto(a11yDto())) { PrefilterTestHarness.rules() }
        assertEquals(A11yProfileResolver.DEFAULT_MERCHANT_LABELS, legacy.a11y.merchantLabels)
        assertTrue(legacy.a11y.appProfiles.isEmpty())
    }

    // ── 快照（逐键回退 + schema 守卫 + 告警）──

    @Test
    @DisplayName("快照：schema=1 → 生成 per-App 策略；schema 不认识 → 空 + warn（带 package/key）")
    fun snapshotResolvesProfilesAndWarnsOnUnknownSchema() {
        val profile = mapOf(
            "com.tencent.mm" to A11yProfileOverride(successKeywords = listOf("已付款"), listPageHeadChars = 40),
        )
        val okRules = rulesWith(profilesSchema = 1, appProfiles = profile)
        val okLogger = mockk<AppLogger>(relaxed = true)
        val okSnap = deriveSnapshot(okRules, generation = 1, logger = okLogger)
        assertEquals(listOf("已付款"), okSnap.a11yPackageProfiles.getValue("com.tencent.mm").successKeywords)
        assertEquals(40, okSnap.a11yPackageProfiles.getValue("com.tencent.mm").listPageHeadChars)
        verify(exactly = 0) { okLogger.warn(any(), any()) }

        val badLogger = mockk<AppLogger>(relaxed = true)
        val badSnap = deriveSnapshot(
            rulesWith(profilesSchema = 2, appProfiles = profile),
            generation = 2,
            logger = badLogger,
        )
        assertTrue(badSnap.a11yPackageProfiles.isEmpty(), "schema 不认识必须忽略整个 app_profiles")
        verify {
            badLogger.warn(
                "A11Y",
                match { it.contains("profiles_schema") },
            )
        }
    }

    @Test
    @DisplayName("快照：单键非法（regex 编译失败）→ 回退全局 + warn（package+key）")
    fun snapshotWarnsOnInvalidProfileKey() {
        val rules = rulesWith(
            profilesSchema = 1,
            appProfiles = mapOf("com.tencent.mm" to A11yProfileOverride(amountRegex = "[(")),
        )
        val logger = mockk<AppLogger>(relaxed = true)
        val snap = deriveSnapshot(rules, generation = 1, logger = logger)
        // 回退到全局 or 内置，但必须有该包的策略且告警带 key
        assertTrue(snap.a11yPackageProfiles.containsKey("com.tencent.mm"))
        verify {
            logger.warn("A11Y", match { it.contains("amount_regex") && it.contains("com.tencent.mm") })
        }
    }

    // ── 预筛按包消费 list_page_head_chars ──

    @Test
    @DisplayName("预筛：per-App headChars 覆盖内置 24（列表词在标题区外时不再误拦）")
    fun prefilterUsesPerProfileHeadChars() = runTest {
        // 「账单」在第 11 个字符处：24 窗口内 → 拦；覆盖成 4 → 窗口外 → 放行
        val page = "0123456789账单 ¥1.00 ¥2.00 ¥3.00"

        val builtin = PrefilterTestHarness.newPrefilter(rulesWith(profilesSchema = null, appProfiles = emptyMap()))
        assertEquals(
            BlockReason.LIST_PAGE,
            ((builtin.evaluate(capture(page))) as Verdict.Block).reason,
        )

        val narrowed = PrefilterTestHarness.newPrefilter(
            rulesWith(
                profilesSchema = 1,
                appProfiles = mapOf("com.tencent.mm" to A11yProfileOverride(listPageHeadChars = 4)),
            )
        )
        assertTrue(narrowed.evaluate(capture(page)) is Verdict.Pass, "headChars=4 时标题区不含列表词，应放行")
    }

    private fun capture(pageText: String) = Capture(
        channel = SourceChannel.A11Y,
        packageName = "com.tencent.mm",
        title = "支付成功",
        fullText = "支付成功 ¥12.00 星巴克",
        receivedAt = 1_000_000L,
        pageText = pageText,
    )

    private fun rulesWith(
        profilesSchema: Int?,
        appProfiles: Map<String, A11yProfileOverride>,
    ): NotificationRules {
        val base = PrefilterTestHarness.rules()
        return base.copy(
            a11y = base.a11y.copy(
                embeddedPaymentApps = listOf("com.tencent.mm"),
                profilesSchema = profilesSchema,
                appProfiles = appProfiles,
            ),
        )
    }
}
