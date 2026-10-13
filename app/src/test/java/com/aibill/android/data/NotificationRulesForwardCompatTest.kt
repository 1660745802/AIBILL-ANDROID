package com.aibill.android.data

import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.domain.notification.PrecheckSpec
import com.aibill.android.data.notification.rules.NotificationRulesMapping
import com.squareup.moshi.Moshi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 云控载荷**向前兼容**护栏：服务端加了客户端还不认识的键时，
 * 客户端必须「忽略新键、完整保留旧键」——而不是崩、也不是把旧规则丢掉。
 *
 * ## 为什么值得单独守
 *
 * 这是本项目踩过的坑的同一类：`parse_log_id` 曾因 DTO 没声明而被 Moshi 静默丢弃，
 * 断掉整条反馈闭环且**不报错**。云控载荷是同一个机制（Moshi + `@JsonClass`），
 * 而服务端正在往里加键（`precheck`、`testcases`…）。
 *
 * 契约约定（跨端 §7）：
 * - 服务端只加键、不改既有键语义，新增键一律可选且有默认值；
 * - 客户端**必须忽略未知键**（Moshi 不开 `failOnUnknown`）。
 *
 * 这里用**接近真实的增量载荷**验证这条约定：新键存在时，
 * `nls`/`a11y`/`processor` 的解析结果与没有新键时**逐字相同**。
 *
 * ⚠️ 注意：本用例只保证「不崩、不丢」。**消费** `precheck`（客户端跑同源判据并按
 * `rule_id` 计数/上报）是独立的一步（契约 §8 ④/⑤），做完之前客户端会忽略它，
 * 因此不会出现「半用」状态。
 */
class NotificationRulesForwardCompatTest {

    private val moshi: Moshi = Moshi.Builder().build()

    private fun parse(json: String) = NotificationRulesMapping.fromDto(
        moshi.adapter(NotificationRulesData::class.java).fromJson(json)
            ?.rules ?: error("载荷缺 rules")
    ) { error("本用例的载荷自带全部结构键，不应回退到内置兜底") }

    /** 基线：只有客户端认识的键 */
    private val baselineJson = """
        {"version":11,"rules":{
          "nls":{"payment_signal_regex":"支付|消费|到账",
                 "wechat":{"package_name":"com.tencent.mm","direct_pass_titles":["微信支付"],
                           "direct_pass_title_contains":[],"message_prefixes":[],"amount_symbols":["¥"]},
                 "alipay":{"package_name":"com.eg.android.AlipayGphone","allowed_title_keywords":["交易提醒"]},
                 "bank_package_patterns":["cmb"],"sms_packages":["com.android.mms"],
                 "per_package":[],"default_rule":{"exclude_content_contains":[]}},
          "a11y":{"embedded_payment_apps":["com.tencent.mm"],"success_keywords":["支付成功"],
                  "embedded_success_keywords":[],"common_exclude_keywords":[],"wechat_alipay_exclude_keywords":[],
                  "amount_regex":"[¥￥][ \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]*(\\d+\\.?\\d{0,2})","cooldown_minutes":5},
          "sms":{"spam_keywords":["退订"]},
          "source_mapping":{"com.tencent.mm":"微信"},
          "processor":{"scoring_window_seconds":10,"dedup_window_seconds":60,
                       "marketing_suffix_cutoffs":["点击领取"],"marketing_comma_keywords":["红包"],
                       "max_amount_cents":10000000,"min_amount_cents":1}
        }}
    """.trimIndent()

    /** 增量载荷：服务端新键（precheck / testcases / 以及一个未来才会出现的未知键） */
    private val withNewKeysJson = baselineJson.replace(
        "\"processor\":{\"scoring_window_seconds\"",
        """
        "precheck":{"schema":1,"derived_from":"ai/verify.ts","channel_agnostic":true,
                    "signals":{"money":{"pattern":"rmb|¥","flags":"gi"},
                               "verb":{"pattern":"支付|消费","flags":"i"},
                               "bare_amount":{"pattern":"[0-9]+","flags":"i"},
                               "bare_amount_max_len":30},
                    "rules":[{"id":"precheck.strong_noise","tier":0,"type":"regex","pattern":"验证码","flags":"i"},
                             {"id":"precheck.money_verb","tier":1,"type":"predicate","expr":"money && verb"},
                             {"id":"precheck.no_signal","tier":4,"type":"fallback"}],
                    "should_call_ai":"tier in (1,2,3)","corroboration_required":"tier in (2,3)"},
        "testcases":[{"id":"nls-wechat-paid","text":"微信支付 已支付¥32.50","expect_txn":true}],
        "some_future_key_the_client_knows_nothing_about":{"nested":[1,2,3]},
        "processor":{"scoring_window_seconds"
        """.trimIndent()
    )

    @Test
    @DisplayName("服务端新增 precheck/testcases/未知键 → 客户端不崩且旧规则逐字不变")
    fun unknownKeysAreIgnoredWithoutLosingKnownRules() {
        val baseline = parse(baselineJson)
        val withNew = parse(withNewKeysJson)

        assertEquals(baseline.nls.paymentSignalRegex, withNew.nls.paymentSignalRegex)
        assertEquals(baseline.nls.bankPackagePatterns, withNew.nls.bankPackagePatterns)
        assertEquals(baseline.nls.perPackage, withNew.nls.perPackage)
        assertEquals(baseline.a11y.embeddedPaymentApps, withNew.a11y.embeddedPaymentApps)
        assertEquals(baseline.a11y.amountRegex, withNew.a11y.amountRegex)
        assertEquals(baseline.a11y.cooldownMinutes, withNew.a11y.cooldownMinutes)
        assertEquals(baseline.sms.spamKeywords, withNew.sms.spamKeywords)
        assertEquals(baseline.sourceMapping, withNew.sourceMapping)
        assertEquals(baseline.processor, withNew.processor)
        // ④ 落地后 precheck 是**已知键**：新载荷要真被消费，旧载荷保持 EMPTY（行为不变）
        assertEquals(PrecheckSpec.EMPTY, baseline.precheck)
        assertEquals(1, withNew.precheck.schema)
        assertEquals(3, withNew.precheck.rules.size)
    }

    @Test
    @DisplayName("未知键不会把已知键「挤掉」：派生快照仍能正常工作")
    fun derivedSnapshotStillWorksWithNewKeys() {
        val rules = parse(withNewKeysJson)
        // 派生判据链（生产路径）：能编译出支付信号正则且认得常见扣款文案
        assertTrue(rules.nls.paymentSignalRegex.isNotBlank())
        assertTrue(
            Regex(rules.nls.paymentSignalRegex).containsMatchIn("您尾号1234的储蓄卡消费100.00元"),
            "支付信号正则认不出银行扣款短信 → 可能与未知键无关，但值得先看这条",
        )
    }
}
