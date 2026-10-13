package com.aibill.android.data.notification.rules

import android.app.Application
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.notification.rules.A11yRules
import com.aibill.android.domain.notification.rules.AlipayRules
import com.aibill.android.domain.notification.rules.NlsRules
import com.aibill.android.domain.notification.rules.NotificationRules
import com.aibill.android.domain.notification.rules.ProcessorRules
import com.aibill.android.domain.notification.rules.SmsRules
import com.aibill.android.domain.notification.rules.WechatRules
import com.squareup.moshi.Moshi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 默认规则来源（C04 决策 3/4：从 [NotificationRulesManager] 拆出的第三块）。
 *
 * 两层兜底：
 * - **assets/`default_rules.json`**：冻结的默认规则（可读可改，随 APK 发布）；
 * - **内联 `emptyFallback`**：assets 也读不到时的最小可用形状（fail-safe 到底）。
 *
 * C04 决策 4（4a）计划把 `emptyFallback` 的手写形状换成「模型字段自带默认值」，
 * 本次先按现状原样搬迁。
 */
@Singleton
class RulesDefaults @Inject constructor(
    private val application: Application,
    private val moshi: Moshi,
) {
    /** 默认规则（assets 优先，失败回落内联兜底）。惰性：只在真正需要兜底时读 assets。 */
    val rules: NotificationRules by lazy { load() }

    private fun load(): NotificationRules = try {
        val json = application.assets.open("default_rules.json").bufferedReader().readText()
        // default_rules.json 的结构是 {version, rules: {...}}，取 rules 部分
        val adapter = moshi.adapter(NotificationRulesData::class.java)
        val fileDto = adapter.fromJson(json)
        if (fileDto?.rules != null) {
            NotificationRulesMapping.fromDto(fileDto.rules) { emptyFallback }
        } else {
            Timber.w("NotificationRules: assets default_rules.json parse returned null, using empty fallback")
            emptyFallback
        }
    } catch (e: Exception) {
        Timber.e(e, "NotificationRules: failed to read assets/default_rules.json")
        emptyFallback
    }

    /** 极端兜底（assets 也读不到时）。 */
    private val emptyFallback = NotificationRules(
        nls = NlsRules(
            paymentSignalRegex = "[¥￥]|支付|付款|到账|转账|消费|扣款|充值|退款",
            wechat = WechatRules("com.tencent.mm", listOf("微信支付"), listOf("零钱"), listOf("[转账]", "[微信红包]"), listOf("¥", "￥")),
            alipay = AlipayRules("com.eg.android.AlipayGphone", listOf("交易提醒", "支付", "账单")),
            bankPackagePatterns = listOf("bank"),
            smsPackages = listOf("com.android.mms"),
        ),
        a11y = A11yRules(
            embeddedPaymentApps = emptyList(),
            successKeywords = listOf("支付成功"),
            embeddedSuccessKeywords = emptyList(),
            commonExcludeKeywords = emptyList(),
            wechatAlipayExcludeKeywords = emptyList(),
            amountRegex = "[¥￥][ \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*(\\d+\\.?\\d{0,2})",
            cooldownMinutes = 5,
            listPageKeywords = A11yRecognition.DEFAULT_LIST_KEYWORDS,
            listPageMinAmounts = A11yRecognition.LIST_PAGE_MIN_AMOUNTS,
        ),
        sms = SmsRules(spamKeywords = emptyList()),
        sourceMapping = emptyMap(),
        processor = ProcessorRules(10, 60, emptyList(), emptyList(), 10_000_000, 1),
    )
}
