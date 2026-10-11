package com.aibill.android.service

import android.app.Application
import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.util.NotificationSourceMapping
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知规则云控管理器
 *
 * 优先级：内存缓存 → SharedPreferences → 硬编码默认值
 * API 失败或未拉取到时自动 fallback 到默认值，不影响现有行为。
 *
 * 规则版本代际（generation）：每次内存缓存变更时递增，供消费者判断
 * 是否需要刷新本地派生值（Regex、Set 等），避免重复遍历。
 *
 * ⚠️ 本类**只管规则数据的存取**，不含任何判据：放行/拦截判定全在
 * [com.aibill.android.domain.notification.NotificationPrefilter]（预筛 module）。
 * 原来的 `isLikelyMarketing` 已随预筛收口删除（收口后零调用点）；
 * 「营销词 + 强交易特征豁免」的判据在 `domain/notification/MarketingCriteria`。
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
     * [fetchRules] 的结果。
     *
     * 以前 fetchRules() 吞掉所有异常返回 Unit，设置页只能无条件弹「规则同步成功」——
     * 断网点同步同样报成功，是**假反馈**，比没有反馈更糟（用户以为规则已是新版）。
     * 现在把「真更新 / 已是最新 / 拉取失败」三态显式返回给调用方。
     */
    sealed interface RulesFetchResult {
        /** 服务端下发了新规则，已写入本地并生效。 */
        data class Updated(val version: Int) : RulesFetchResult

        /** ETag 命中 304，本地规则本来就是最新的。 */
        data object NotModified : RulesFetchResult

        /** 拉取失败，**保留现有规则继续工作**（不影响已记账功能）。 */
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

    fun getRulesGeneration(): Int = getSnapshot().generation

    /**
     * 从服务器拉取规则。支持 ETag/304，304 不更新。
     *
     * **不抛异常**：失败时返回 [RulesFetchResult.Failed] 并保留现有规则，
     * 保证「云控挂了不影响已记账功能」这一原有语义不变。
     *
     * 返回值对 fire-and-forget 的调用方（[AiBillApp] 启动拉取、
     * [RulesSyncWorker] 周期拉取）可以忽略，Kotlin 允许丢弃返回值，
     * 只有需要给用户反馈的设置页才必须区分三态。
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
                        Timber.d("NotificationRules: updated to version=${body.data.version} etag=${newEtag?.take(16)} " +
                            "sourceMapping=${rulesDto.sourceMapping?.size ?: 0} " +
                            "nlsSmsPackages=${rulesDto.nls?.smsPackages?.size ?: 0}")
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

    /**
     * 获取当前规则（同步，不阻塞）。
     * 优先级：内存缓存 → SharedPreferences → 硬编码默认值
     */
    fun getRules(): NotificationRules = getSnapshot().rules

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
        cachedAllKnownPackages = null
        cachedAllKnownPackagesGeneration = -1
        Timber.d("NotificationRules: cache updated, generation=$generation")
    }

    // ═══════════════════════════════════════════════════════════════
    // 动态包名过滤器（供 NLS 服务使用）
    // ═══════════════════════════════════════════════════════════════

    /** 缓存 allKnownPackages + 对应的代际，避免每次通知都重建 Set */
    @Volatile
    private var cachedAllKnownPackages: Set<String>? = null
    @Volatile
    private var cachedAllKnownPackagesGeneration: Int = -1

    /**
     * 获取所有已知的支付相关包名（动态，含云控规则）：
     * - 硬编码 KNOWN_PACKAGES（本地 90+ 包名）
     * - 云控 sourceMapping 的 keys（服务端可动态新增）
     * - 云控 nls.smsPackages（短信 App 包名）
     *
     * 结果会按代际缓存，避免每次通知都重建集合。
     *
     * 注：bankPackagePatterns 是匹配模式（如 "bank"、"cmb"），
     * 无法预展开为包名集合，需通过 [isKnownOrBankPackage] 做实时匹配。
     */
    fun getAllKnownPackages(): Set<String> {
        val snapshot = getSnapshot()
        val gen = snapshot.generation
        val cached = cachedAllKnownPackages
        if (cached != null && cachedAllKnownPackagesGeneration == gen) {
            return cached
        }
        val rules = snapshot.rules
        val result = mutableSetOf<String>()
        result.addAll(NotificationSourceMapping.KNOWN_PACKAGES)
        result.addAll(rules.sourceMapping.keys)
        result.addAll(rules.nls.smsPackages)
        cachedAllKnownPackages = result
        cachedAllKnownPackagesGeneration = gen
        Timber.d("NotificationRules: getAllKnownPackages " +
            "hardcoded=${NotificationSourceMapping.KNOWN_PACKAGES.size} " +
            "sourceMapping=${rules.sourceMapping.size} " +
            "smsPackages=${rules.nls.smsPackages.size} " +
            "total=${result.size} gen=$gen")
        return result
    }

    /**
     * 判断包名是否属于已知支付 App 或匹配银行包名模式。
     *
     * 用于 NLS 白名单动态判断，替代静态 [NotificationSourceMapping.KNOWN_PACKAGES]。
     *
     * @return true 如果包名在白名单中，或匹配 bankPackagePatterns 中的任一模式
     */
    fun isKnownOrBankPackage(packageName: String): Boolean {
        // 1. 已知包名（硬编码 + 云控 sourceMapping + smsPackages）
        if (packageName in getAllKnownPackages()) {
            return true
        }
        // 2. 银行包名模式匹配（如 "bank"、"cmb" 等前缀/包含匹配）
        val rules = getSnapshot().rules
        for (pattern in rules.nls.bankPackagePatterns) {
            if (pattern.isNotBlank() && packageName.contains(pattern, ignoreCase = true)) {
                Timber.d("NotificationRules: package=$packageName matched bank pattern='$pattern'")
                return true
            }
        }
        return false
    }

    /**
     * 云控 DTO → 规则模型。映射体见 [NotificationRulesMapping]——
     * 抽成顶层函数是为了让单测能直接把 `scripts/rules.json` 喂进同一条映射
     * （复制一份映射 = 回归护栏锁的是拷贝，正是本次收口要消灭的东西）。
     *
     * `fallback` 是惰性的：只有云控缺字段时才会读 assets/硬编码兜底。
     */
    private fun mapDtoToRules(dto: NotificationRulesDto): NotificationRules =
        NotificationRulesMapping.fromDto(dto) { defaultRules }

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
                emptyFallback
            }
        } catch (e: Exception) {
            Timber.e(e, "NotificationRules: failed to read assets/default_rules.json")
            emptyFallback
        }
    }

    /** 极端兜底（assets也读不到时） */
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
            amountRegex = "[¥￥]\\s*(\\d+\\.?\\d{0,2})",
            cooldownMinutes = 5,
        ),
        sms = SmsRules(spamKeywords = emptyList()),
        sourceMapping = emptyMap(),
        processor = ProcessorRules(10, 60, emptyList(), emptyList(), 10_000_000, 1),
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
    val perPackage: List<PerPackageRule> = emptyList(),
    val defaultExcludeContent: List<String> = emptyList(),
)

data class PerPackageRule(
    val packageName: String? = null,
    val packagePattern: String? = null,
    val passAll: Boolean = false,
    val passTitleExact: List<String> = emptyList(),
    val passTitleContains: List<String> = emptyList(),
    val passMsgPrefix: List<String> = emptyList(),
    val requireAmountSymbol: Boolean = false,
    val excludeTitleContains: List<String> = emptyList(),
    val excludeContentContains: List<String> = emptyList(),
) {
    fun matches(pkg: String): Boolean {
        if (packageName != null && packageName == pkg) return true
        if (packagePattern != null && packagePattern.isNotBlank()) {
            return pkg.contains(packagePattern) || pkg.startsWith(packagePattern)
        }
        return false
    }
}

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
    val marketingCommaKeywords: List<String>,
    val maxAmountCents: Int,
    val minAmountCents: Int,
)
