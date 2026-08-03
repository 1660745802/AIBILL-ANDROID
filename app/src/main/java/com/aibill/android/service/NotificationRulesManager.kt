package com.aibill.android.service

import android.app.Application
import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.squareup.moshi.Moshi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知规则云控管理器
 *
 * 优先级：内存缓存 → SharedPreferences → 硬编码默认值
 * API 失败或未拉取到时自动 fallback 到默认值，不影响现有行为。
 */
@Singleton
class NotificationRulesManager @Inject constructor(
    private val api: NotificationRulesApi,
    private val application: Application,
    private val moshi: Moshi,
) {
    private val prefs by lazy {
        application.getSharedPreferences("notification_rules", android.content.Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedRules: NotificationRules? = null

    /**
     * 从服务器拉取规则。支持 ETag/304，304 不更新。
     * 静默处理所有异常，不影响正常流程。
     */
    suspend fun fetchRules() {
        try {
            val etag = prefs.getString(KEY_ETAG, null)
            val response = api.getRules(etag)

            when (response.code()) {
                304 -> {
                    Timber.d("NotificationRules: 304 Not Modified")
                }
                200 -> {
                    val body = response.body()
                    if (body?.code == 0 && body.data?.rules != null) {
                        val rulesDto = body.data.rules
                        val json = moshi.adapter(NotificationRulesDto::class.java).toJson(rulesDto)
                        prefs.edit()
                            .putString(KEY_JSON, json)
                            .putString(KEY_ETAG, response.headers()["ETag"])
                            .apply()
                        cachedRules = mapDtoToRules(rulesDto)
                        Timber.d("NotificationRules: updated to version ${body.data.version}")
                    }
                }
                else -> {
                    Timber.w("NotificationRules: unexpected HTTP ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "NotificationRules: fetch failed, using cached/default")
        }
    }

    /**
     * 获取当前规则（同步，不阻塞）。
     * 优先级：内存缓存 → SharedPreferences → 硬编码默认值
     */
    fun getRules(): NotificationRules {
        cachedRules?.let { return it }

        val json = prefs.getString(KEY_JSON, null)
        if (json != null) {
            try {
                val dto = moshi.adapter(NotificationRulesDto::class.java).fromJson(json)
                if (dto != null) {
                    val rules = mapDtoToRules(dto)
                    cachedRules = rules
                    return rules
                }
            } catch (e: Exception) {
                Timber.w(e, "NotificationRules: parse cached JSON failed")
            }
        }

        return defaultRules
    }

    private fun mapDtoToRules(dto: NotificationRulesDto): NotificationRules {
        return NotificationRules(
            nls = NlsRules(
                paymentSignalRegex = dto.nls?.paymentSignalRegex ?: defaultRules.nls.paymentSignalRegex,
                wechat = WechatRules(
                    packageName = dto.nls?.wechat?.packageName ?: defaultRules.nls.wechat.packageName,
                    directPassTitles = dto.nls?.wechat?.directPassTitles ?: defaultRules.nls.wechat.directPassTitles,
                    directPassTitleContains = dto.nls?.wechat?.directPassTitleContains ?: defaultRules.nls.wechat.directPassTitleContains,
                    messagePrefixes = dto.nls?.wechat?.messagePrefixes ?: defaultRules.nls.wechat.messagePrefixes,
                    amountSymbols = dto.nls?.wechat?.amountSymbols ?: defaultRules.nls.wechat.amountSymbols,
                ),
                alipay = AlipayRules(
                    packageName = dto.nls?.alipay?.packageName ?: defaultRules.nls.alipay.packageName,
                    allowedTitleKeywords = dto.nls?.alipay?.allowedTitleKeywords ?: defaultRules.nls.alipay.allowedTitleKeywords,
                ),
                bankPackagePatterns = dto.nls?.bankPackagePatterns ?: defaultRules.nls.bankPackagePatterns,
                smsPackages = dto.nls?.smsPackages ?: defaultRules.nls.smsPackages,
            ),
            a11y = A11yRules(
                embeddedPaymentApps = dto.a11y?.embeddedPaymentApps ?: defaultRules.a11y.embeddedPaymentApps,
                successKeywords = dto.a11y?.successKeywords ?: defaultRules.a11y.successKeywords,
                embeddedSuccessKeywords = dto.a11y?.embeddedSuccessKeywords ?: defaultRules.a11y.embeddedSuccessKeywords,
                commonExcludeKeywords = dto.a11y?.commonExcludeKeywords ?: defaultRules.a11y.commonExcludeKeywords,
                wechatAlipayExcludeKeywords = dto.a11y?.wechatAlipayExcludeKeywords ?: defaultRules.a11y.wechatAlipayExcludeKeywords,
                amountRegex = dto.a11y?.amountRegex ?: defaultRules.a11y.amountRegex,
                cooldownMinutes = dto.a11y?.cooldownMinutes ?: defaultRules.a11y.cooldownMinutes,
            ),
            sms = SmsRules(
                spamKeywords = dto.smsRules?.spamKeywords ?: defaultRules.sms.spamKeywords,
            ),
            sourceMapping = dto.sourceMapping ?: defaultRules.sourceMapping,
            processor = ProcessorRules(
                scoringWindowSeconds = dto.processor?.scoringWindowSeconds ?: defaultRules.processor.scoringWindowSeconds,
                dedupWindowSeconds = dto.processor?.dedupWindowSeconds ?: defaultRules.processor.dedupWindowSeconds,
                marketingSuffixCutoffs = dto.processor?.marketingSuffixCutoffs ?: defaultRules.processor.marketingSuffixCutoffs,
                maxAmountCents = dto.processor?.maxAmountCents ?: defaultRules.processor.maxAmountCents,
                minAmountCents = dto.processor?.minAmountCents ?: defaultRules.processor.minAmountCents,
            ),
        )
    }

    companion object {
        private const val KEY_JSON = "notification_rules_json"
        private const val KEY_ETAG = "notification_rules_etag"
    }

    /**
     * 默认规则：从 assets/default_rules.json 读取（编译时从 scripts/rules.json 同步）。
     * 只维护一份文件，避免硬编码和脚本不一致。
     */
    private val defaultRules: NotificationRules by lazy {
        try {
            val json = application.assets.open("default_rules.json").bufferedReader().readText()
            // default_rules.json 的结构是 {version, rules: {...}}，取 rules 部分
            val adapter = moshi.adapter(NotificationRulesData::class.java)
            val fileDto = adapter.fromJson(json)
            if (fileDto?.rules != null) {
                mapDtoToRules(fileDto.rules)
            } else {
                Timber.w("NotificationRules: assets default_rules.json parse returned null, using empty fallback")
                EMPTY_FALLBACK
            }
        } catch (e: Exception) {
            Timber.e(e, "NotificationRules: failed to read assets/default_rules.json")
            EMPTY_FALLBACK
        }
    }

    /** 极端兜底（assets也读不到时） */
    private val EMPTY_FALLBACK = NotificationRules(
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
            amountRegex = "[¥￥]\\s*(\\d+\\.?\\d{0,2})",
            cooldownMinutes = 5,
        ),
        sms = SmsRules(spamKeywords = emptyList()),
        sourceMapping = emptyMap(),
        processor = ProcessorRules(10, 60, emptyList(), 10_000_000, 1),
    )
}

// ═══════════════════════════════════════════════════════════════
// 规则数据模型
// ═══════════════════════════════════════════════════════════════

data class NotificationRules(
    val nls: NlsRules,
    val a11y: A11yRules,
    val sms: SmsRules,
    val sourceMapping: Map<String, String>,
    val processor: ProcessorRules,
)

data class NlsRules(
    val paymentSignalRegex: String,
    val wechat: WechatRules,
    val alipay: AlipayRules,
    val bankPackagePatterns: List<String>,
    val smsPackages: List<String>,
)

data class WechatRules(
    val packageName: String,
    val directPassTitles: List<String>,
    val directPassTitleContains: List<String>,
    val messagePrefixes: List<String>,
    val amountSymbols: List<String>,
)

data class AlipayRules(
    val packageName: String,
    val allowedTitleKeywords: List<String>,
)

data class A11yRules(
    val embeddedPaymentApps: List<String>,
    val successKeywords: List<String>,
    val embeddedSuccessKeywords: List<String>,
    val commonExcludeKeywords: List<String>,
    val wechatAlipayExcludeKeywords: List<String>,
    val amountRegex: String,
    val cooldownMinutes: Int,
)

data class SmsRules(
    val spamKeywords: List<String>,
)

data class ProcessorRules(
    val scoringWindowSeconds: Int,
    val dedupWindowSeconds: Int,
    val marketingSuffixCutoffs: List<String>,
    val maxAmountCents: Int,
    val minAmountCents: Int,
)
