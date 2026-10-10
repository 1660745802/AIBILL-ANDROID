package com.aibill.android.service

import android.app.Application
import android.content.SharedPreferences
import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.NotificationRulesResponse
import com.aibill.android.data.remote.dto.response.NlsRulesDto
import com.aibill.android.data.remote.dto.response.A11yRulesDto
import com.aibill.android.data.remote.dto.response.SmsRulesDto
import com.aibill.android.data.remote.dto.response.ProcessorRulesDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * NotificationRulesManager 单元测试
 *
 * 覆盖：
 * 1. getRules() 内存缓存命中 → 直接返回
 * 2. getRules() 无内存缓存 + SP有数据 → 解析返回
 * 3. getRules() 无内存无SP → 返回 assets 默认值
 * 4. fetchRules() 200 → 更新 SP + 内存缓存，返回 Updated(version)
 * 5. fetchRules() 304 → 不更新，返回 NotModified
 * 6. fetchRules() 网络异常 → 静默失败，返回 Failed（**不谎报成功**）
 * 7. SP 中 JSON 格式错误 → fallback 到默认值
 * 8. 三态反馈准确性 —— 设置页「同步记账规则」直接依赖返回值区分成功/最新/失败
 */
class NotificationRulesManagerTest {

    private val api: NotificationRulesApi = mockk()
    private val application: Application = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)

    private lateinit var manager: NotificationRulesManager

    private val moshi = com.squareup.moshi.Moshi.Builder().build()

    @BeforeEach
    fun setUp() {
        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { application.assets } returns mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        manager = NotificationRulesManager(api, application, moshi)
    }

    @Test
    @DisplayName("1. getRules() - 内存缓存命中直接返回")
    fun getRules_memoryCache() = runTest {
        // 先 fetch 一次让内存有值
        val dto = makeMinimalDto()
        val response = Response.success(
            NotificationRulesResponse(code = 0, data = NotificationRulesData(version = 1, updatedAt = null, rules = dto)),
            Headers.headersOf("ETag", "\"1\"")
        )
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } returns response

        manager.fetchRules()

        // 第二次调 getRules() 应该不读 SP
        val rules = manager.getRules()
        assertNotNull(rules)
        assertEquals("支付成功", rules.a11y.successKeywords.first())
    }

    @Test
    @DisplayName("2. getRules() - SP有数据时解析返回")
    fun getRules_fromSharedPreferences() {
        val json = """{"nls":{"payment_signal_regex":"¥|支付"},"a11y":{"success_keywords":["测试关键词"],"embedded_payment_apps":[],"embedded_success_keywords":[],"common_exclude_keywords":[],"wechat_alipay_exclude_keywords":[],"amount_regex":"¥","cooldown_minutes":5},"sms":{"spam_keywords":[]},"processor":{"scoring_window_seconds":10,"dedup_window_seconds":60,"marketing_suffix_cutoffs":[],"marketing_comma_keywords":[],"max_amount_cents":1000000,"min_amount_cents":1}}"""
        every { prefs.getString("notification_rules_json", null) } returns json
        every { prefs.getString("notification_rules_etag", null) } returns null

        val rules = manager.getRules()
        assertTrue(rules.a11y.successKeywords.contains("测试关键词"))
    }

    @Test
    @DisplayName("3. getRules() - SP为空时返回默认值")
    fun getRules_fallbackToDefault() {
        every { prefs.getString(any(), any()) } returns null

        val rules = manager.getRules()
        // 默认值从 assets 读取或用 EMPTY_FALLBACK
        assertNotNull(rules)
        assertNotNull(rules.nls.paymentSignalRegex)
    }

    @Test
    @DisplayName("4. fetchRules() 200 → 更新SP和内存缓存")
    fun fetchRules_200_updatesCache() = runTest {
        val dto = makeMinimalDto()
        val response = Response.success(
            NotificationRulesResponse(code = 0, data = NotificationRulesData(version = 2, updatedAt = "2026-08-01", rules = dto)),
            Headers.headersOf("ETag", "\"2\"")
        )
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } returns response

        val result = manager.fetchRules()

        // 验证 SP 被写入
        verify { editor.putString("notification_rules_json", any()) }
        verify { editor.putString("notification_rules_etag", "\"2\"") }
        verify { editor.apply() }

        // 验证内存缓存生效
        val rules = manager.getRules()
        assertEquals("支付成功", rules.a11y.successKeywords.first())

        // 设置页靠这个返回值告诉用户「更新到哪一版」
        assertEquals(NotificationRulesManager.RulesFetchResult.Updated(2), result)
    }

    @Test
    @DisplayName("5. fetchRules() 非200状态码 → 不更新SP")
    fun fetchRules_non200_noUpdate() = runTest {
        every { prefs.getString(any(), any()) } returns null

        // 模拟 500 错误
        coEvery { api.getRules(any()) } returns Response.error(
            500,
            okhttp3.ResponseBody.create(null, "Internal Server Error")
        )

        io.mockk.clearMocks(editor, answers = false)
        val result = manager.fetchRules()

        verify(exactly = 0) { editor.putString("notification_rules_json", any()) }
        assertTrue(result is NotificationRulesManager.RulesFetchResult.Failed)
    }

    @Test
    @DisplayName("6. fetchRules() 网络异常 → 静默失败")
    fun fetchRules_networkError_silentFail() = runTest {
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } throws java.io.IOException("Network timeout")

        // 不应该抛异常
        val result = manager.fetchRules()

        // getRules() 仍能返回默认值
        val rules = manager.getRules()
        assertNotNull(rules)

        // ★ 回归：以前这里返回 Unit，设置页无条件弹「规则同步成功」——
        // 断网也报成功。必须能区分出失败。
        val failed = result as NotificationRulesManager.RulesFetchResult.Failed
        assertTrue(failed.message.contains("网络"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 反馈准确性：设置页「同步记账规则」的三态反馈
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("7. fetchRules() 304 → NotModified（而不是谎报已更新）")
    fun fetchRules_304_reportsNotModified() = runTest {
        every { prefs.getString(any(), any()) } returns null
        // Retrofit 的 error(int, body) 要求 code >= 400，拿不到 304；
        // 用带 raw response 的重载才能构造出 304（ETag 命中时的真实响应）。
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.invalid/rules").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(304)
            .message("Not Modified")
            .build()
        coEvery { api.getRules(any()) } returns Response.error(
            okhttp3.ResponseBody.create(null, "not modified"),
            raw,
        )

        val result = manager.fetchRules()

        assertEquals(NotificationRulesManager.RulesFetchResult.NotModified, result)
    }

    @Test
    @DisplayName("8. fetchRules() 200 但 body 异常 → Failed")
    fun fetchRules_200_badBody_reportsFailed() = runTest {
        every { prefs.getString(any(), any()) } returns null
        // code != 0：服务端业务失败
        coEvery { api.getRules(any()) } returns Response.success(
            NotificationRulesResponse(code = 5001, data = null)
        )

        val result = manager.fetchRules()

        assertTrue(result is NotificationRulesManager.RulesFetchResult.Failed)
    }

    @Test
    @DisplayName("9. fetchRules() 500 → Failed 且带状态码")
    fun fetchRules_500_reportsFailedWithCode() = runTest {
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } returns Response.error(
            503,
            okhttp3.ResponseBody.create(null, "Service Unavailable")
        )

        val result = manager.fetchRules() as NotificationRulesManager.RulesFetchResult.Failed

        assertTrue(result.message.contains("503"))
    }

    @Test
    @DisplayName("10. fetchRules() 失败时仍不抛异常——云控挂了不能影响已记账功能")
    fun fetchRules_failure_neverThrows() = runTest {
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } throws IllegalStateException("boom")

        val result = manager.fetchRules()

        assertTrue(result is NotificationRulesManager.RulesFetchResult.Failed)
        assertNotNull(manager.getRules())
    }

    @Test
    @DisplayName("7. 动态 package filter 包含云控映射、短信包和银行模式")
    fun dynamicPackageFilter_usesCloudRules() = runTest {
        val dto = makeMinimalDto().copy(
            sourceMapping = mapOf("com.example.wallet" to "示例钱包"),
            nls = makeMinimalDto().nls?.copy(
                smsPackages = listOf("com.example.sms"),
                bankPackagePatterns = listOf("newbank"),
            ),
        )
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } returns Response.success(
            NotificationRulesResponse(code = 0, data = NotificationRulesData(version = 3, updatedAt = null, rules = dto)),
        )

        manager.fetchRules()

        assertTrue(manager.isKnownOrBankPackage("com.example.wallet"))
        assertTrue(manager.isKnownOrBankPackage("com.example.sms"))
        assertTrue(manager.isKnownOrBankPackage("com.vendor.newbank.mobile"))
        assertFalse(manager.isKnownOrBankPackage("com.example.unrelated"))
        assertEquals(manager.getSnapshot().rules, manager.getRules())
        assertEquals(manager.getSnapshot().generation, manager.getRulesGeneration())
    }

    @Test
    @DisplayName("空银行模式不会匹配所有包名")
    fun blankBankPattern_doesNotMatchEverything() = runTest {
        val dto = makeMinimalDto().copy(
            nls = makeMinimalDto().nls?.copy(bankPackagePatterns = listOf(" ", "")),
        )
        every { prefs.getString(any(), any()) } returns null
        coEvery { api.getRules(any()) } returns Response.success(
            NotificationRulesResponse(code = 0, data = NotificationRulesData(version = 4, updatedAt = null, rules = dto)),
        )
        manager.fetchRules()
        assertFalse(manager.isKnownOrBankPackage("com.example.unrelated"))
    }

    @Test
    @DisplayName("11. SP中JSON格式错误 → fallback默认值")
    fun getRules_corruptedSp_fallbackToDefault() {
        every { prefs.getString("notification_rules_json", null) } returns "{{invalid json}}"
        every { prefs.getString("notification_rules_etag", null) } returns null

        val rules = manager.getRules()
        assertNotNull(rules)
        // 应该返回默认值而非崩溃
        assertNotNull(rules.nls.paymentSignalRegex)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun makeMinimalDto() = NotificationRulesDto(
        nls = NlsRulesDto(
            paymentSignalRegex = "¥|支付",
            wechat = null,
            alipay = null,
            bankPackagePatterns = listOf("bank"),
            smsPackages = listOf("com.android.mms"),
            perPackage = null,
            defaultRule = null,
        ),
        a11y = A11yRulesDto(
            embeddedPaymentApps = listOf("me.ele"),
            successKeywords = listOf("支付成功"),
            embeddedSuccessKeywords = listOf("订单支付成功"),
            commonExcludeKeywords = listOf("购物车"),
            wechatAlipayExcludeKeywords = listOf("朋友圈"),
            amountRegex = "[¥￥]\\s*(\\d+)",
            cooldownMinutes = 5,
        ),
        smsRules = SmsRulesDto(spamKeywords = listOf("订购")),
        sourceMapping = mapOf("com.tencent.mm" to "微信"),
        processor = ProcessorRulesDto(
            scoringWindowSeconds = 10,
            dedupWindowSeconds = 60,
            marketingSuffixCutoffs = listOf("点击领取"),
            marketingCommaKeywords = listOf("红包"),
            maxAmountCents = 10_000_000,
            minAmountCents = 1,
        ),
    )
}
