package com.aibill.android.data.notification.rules

import android.app.Application
import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知规则云控管理器（C04 后位于 data 层）。
 *
 * 优先级：内存缓存 → SharedPreferences → 硬编码默认值。
 * API 失败或未拉取到时自动 fallback 到默认值，不影响现有行为。
 *
 * 规则版本代际（generation）：每次内存缓存变更时递增，供消费者判断是否需要刷新本地派生值。
 *
 * ⚠️ 本类**只管规则数据的存取**，不含任何判据：放行/拦截判定全在
 * [com.aibill.android.domain.notification.NotificationPrefilter]（预筛 module）。
 *
 * C04：它是 SP + Retrofit 的**存储装配物**，故从 `service/` 下沉到 `data/`；规则模型
 * 本身在 `domain/notification/rules/`。C04 决策 3 计划进一步拆成 RulesStore/RulesFetcher/
 * RulesDefaults（本片先完成「模型进 domain、manager 进 data」，拆分与快照单一访问路径随后）。
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

    data class RulesSnapshot(
        val rules: NotificationRules,
        val generation: Int,
    )

    /**
     * [fetchRules] 的结果：区分「真更新 / 已是最新 / 拉取失败」三态，避免断网点同步也报成功（假反馈）。
     */
    sealed interface RulesFetchResult {
        data class Updated(val version: Int) : RulesFetchResult
        data object NotModified : RulesFetchResult
        data class Failed(val message: String) : RulesFetchResult
    }

    @Volatile
    private var cachedSnapshot: RulesSnapshot? = null

    /** 原子读取规则及其代际，避免消费者把旧规则标记为新 generation。 */
    fun getSnapshot(): RulesSnapshot {
        cachedSnapshot?.let { return it }
        return synchronized(this) {
            cachedSnapshot ?: loadInitialRules().also { cachedSnapshot = it }
        }
    }

    /**
     * 从服务器拉取规则。支持 ETag/304，304 不更新。**不抛异常**：失败返回 [RulesFetchResult.Failed]。
     */
    suspend fun fetchRules(): RulesFetchResult {
        return try {
            val etag = prefs.getString(KEY_ETAG, null)
            Timber.d("NotificationRules: fetching from server (etag=${etag?.take(16) ?: "none"})")
            val response = api.getRules(etag)

            when (response.code()) {
                304 -> {
                    Timber.d("NotificationRules: 304 Not Modified, rules unchanged")
                    RulesFetchResult.NotModified
                }
                200 -> {
                    val body = response.body()
                    val rulesDto = body?.data?.rules
                    if (body?.code == 0 && rulesDto != null) {
                        val json = moshi.adapter(NotificationRulesDto::class.java).toJson(rulesDto)
                        val newEtag = response.headers()["ETag"]
                        prefs.edit()
                            .putString(KEY_JSON, json)
                            .putString(KEY_ETAG, newEtag)
                            .apply()
                        setCachedRules(mapDtoToRules(rulesDto))
                        Timber.d(
                            "NotificationRules: updated to version=${body.data.version} etag=${newEtag?.take(16)} " +
                                "sourceMapping=${rulesDto.sourceMapping?.size ?: 0} " +
                                "nlsSmsPackages=${rulesDto.nls?.smsPackages?.size ?: 0}"
                        )
                        RulesFetchResult.Updated(body.data.version)
                    } else {
                        Timber.w("NotificationRules: 200 but code=${body?.code} or rules=null")
                        RulesFetchResult.Failed("服务端返回异常，请稍后重试")
                    }
                }
                else -> {
                    Timber.w("NotificationRules: unexpected HTTP ${response.code()}")
                    RulesFetchResult.Failed("服务器错误 ${response.code()}")
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "NotificationRules: fetch failed, keeping current rules")
            RulesFetchResult.Failed("网络连接失败，请检查服务器地址")
        } catch (e: Exception) {
            Timber.w(e, "NotificationRules: fetch failed, keeping current rules")
            RulesFetchResult.Failed(e.message ?: "未知错误")
        }
    }

    private fun loadInitialRules(): RulesSnapshot {
        val json = prefs.getString(KEY_JSON, null)
        if (json != null) {
            try {
                val dto = moshi.adapter(NotificationRulesDto::class.java).fromJson(json)
                if (dto != null) {
                    val rules = mapDtoToRules(dto)
                    Timber.d("NotificationRules: restored from SP (sourceMapping=${rules.sourceMapping.size})")
                    return RulesSnapshot(rules, 1)
                }
            } catch (e: Exception) {
                Timber.w(e, "NotificationRules: parse cached JSON failed, falling back to defaults")
            }
        }

        Timber.d("NotificationRules: no cached rules, using defaults from assets")
        return RulesSnapshot(defaultRules, 0)
    }

    @Synchronized
    private fun setCachedRules(rules: NotificationRules) {
        val generation = (cachedSnapshot?.generation ?: 0) + 1
        cachedSnapshot = RulesSnapshot(rules, generation)
        Timber.d("NotificationRules: cache updated, generation=$generation")
    }

    /** 云控 DTO → 规则模型。映射体见 [NotificationRulesMapping]。 */
    private fun mapDtoToRules(dto: NotificationRulesDto): NotificationRules =
        NotificationRulesMapping.fromDto(dto) { defaultRules }

    companion object {
        private const val KEY_JSON = "notification_rules_json"
        private const val KEY_ETAG = "notification_rules_etag"
    }

    /** 默认规则：从 assets/default_rules.json 读取。只维护一份文件，避免硬编码和脚本不一致。 */
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
                emptyFallback
            }
        } catch (e: Exception) {
            Timber.e(e, "NotificationRules: failed to read assets/default_rules.json")
            emptyFallback
        }
    }

    /** 极端兜底（assets 也读不到时） */
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
