package com.aibill.android.service

import android.app.Application
import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
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

        return DEFAULT_RULES
    }

    private fun mapDtoToRules(dto: NotificationRulesDto): NotificationRules {
        return NotificationRules(
            nls = NlsRules(
                paymentSignalRegex = dto.nls?.paymentSignalRegex ?: DEFAULT_RULES.nls.paymentSignalRegex,
                wechat = WechatRules(
                    packageName = dto.nls?.wechat?.packageName ?: DEFAULT_RULES.nls.wechat.packageName,
                    directPassTitles = dto.nls?.wechat?.directPassTitles ?: DEFAULT_RULES.nls.wechat.directPassTitles,
                    directPassTitleContains = dto.nls?.wechat?.directPassTitleContains ?: DEFAULT_RULES.nls.wechat.directPassTitleContains,
                    messagePrefixes = dto.nls?.wechat?.messagePrefixes ?: DEFAULT_RULES.nls.wechat.messagePrefixes,
                    amountSymbols = dto.nls?.wechat?.amountSymbols ?: DEFAULT_RULES.nls.wechat.amountSymbols,
                ),
                alipay = AlipayRules(
                    packageName = dto.nls?.alipay?.packageName ?: DEFAULT_RULES.nls.alipay.packageName,
                    allowedTitleKeywords = dto.nls?.alipay?.allowedTitleKeywords ?: DEFAULT_RULES.nls.alipay.allowedTitleKeywords,
                ),
                bankPackagePatterns = dto.nls?.bankPackagePatterns ?: DEFAULT_RULES.nls.bankPackagePatterns,
                smsPackages = dto.nls?.smsPackages ?: DEFAULT_RULES.nls.smsPackages,
            ),
            a11y = A11yRules(
                embeddedPaymentApps = dto.a11y?.embeddedPaymentApps ?: DEFAULT_RULES.a11y.embeddedPaymentApps,
                successKeywords = dto.a11y?.successKeywords ?: DEFAULT_RULES.a11y.successKeywords,
                embeddedSuccessKeywords = dto.a11y?.embeddedSuccessKeywords ?: DEFAULT_RULES.a11y.embeddedSuccessKeywords,
                commonExcludeKeywords = dto.a11y?.commonExcludeKeywords ?: DEFAULT_RULES.a11y.commonExcludeKeywords,
                wechatAlipayExcludeKeywords = dto.a11y?.wechatAlipayExcludeKeywords ?: DEFAULT_RULES.a11y.wechatAlipayExcludeKeywords,
                amountRegex = dto.a11y?.amountRegex ?: DEFAULT_RULES.a11y.amountRegex,
                cooldownMinutes = dto.a11y?.cooldownMinutes ?: DEFAULT_RULES.a11y.cooldownMinutes,
            ),
            sms = SmsRules(
                spamKeywords = dto.smsRules?.spamKeywords ?: DEFAULT_RULES.sms.spamKeywords,
            ),
            sourceMapping = dto.sourceMapping ?: DEFAULT_RULES.sourceMapping,
            processor = ProcessorRules(
                scoringWindowSeconds = dto.processor?.scoringWindowSeconds ?: DEFAULT_RULES.processor.scoringWindowSeconds,
                dedupWindowSeconds = dto.processor?.dedupWindowSeconds ?: DEFAULT_RULES.processor.dedupWindowSeconds,
                marketingSuffixCutoffs = dto.processor?.marketingSuffixCutoffs ?: DEFAULT_RULES.processor.marketingSuffixCutoffs,
                maxAmountCents = dto.processor?.maxAmountCents ?: DEFAULT_RULES.processor.maxAmountCents,
                minAmountCents = dto.processor?.minAmountCents ?: DEFAULT_RULES.processor.minAmountCents,
            ),
        )
    }

    companion object {
        private const val KEY_JSON = "notification_rules_json"
        private const val KEY_ETAG = "notification_rules_etag"

        /** 硬编码默认值，与各 Service 当前逻辑完全一致 */
        val DEFAULT_RULES = NotificationRules(
            nls = NlsRules(
                paymentSignalRegex = "[¥￥\$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|" +
                    "转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|" +
                    "余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销",
                wechat = WechatRules(
                    packageName = "com.tencent.mm",
                    directPassTitles = listOf("微信支付", "微信支付凭证"),
                    directPassTitleContains = listOf("零钱"),
                    messagePrefixes = listOf("[转账]", "[微信红包]"),
                    amountSymbols = listOf("¥", "￥"),
                ),
                alipay = AlipayRules(
                    packageName = "com.eg.android.AlipayGphone",
                    allowedTitleKeywords = listOf("交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款"),
                ),
                bankPackagePatterns = listOf("bank", "cmb", "icbc", "ccb", "boc", "abchina"),
                smsPackages = listOf(
                    "com.android.mms", "com.google.android.apps.messaging",
                    "com.samsung.android.messaging", "com.miui.mms",
                    "com.huawei.message", "com.oppo.mms", "com.vivo.mms"
                ),
            ),
            a11y = A11yRules(
                embeddedPaymentApps = listOf(
                    "me.ele", "com.sankuai.meituan", "com.dianping.v1",
                    "com.taobao.taobao", "com.tmall.wireless", "com.xunmeng.pinduoduo",
                    "cn.damai", "com.taobao.idlefish", "com.autonavi.minimap",
                ),
                successKeywords = listOf("支付成功", "付款成功", "交易成功", "支付完成"),
                embeddedSuccessKeywords = listOf("订单支付成功", "等待商家接单", "订单已提交"),
                commonExcludeKeywords = listOf(
                    "购物车", "加入购物车", "立即购买", "去支付", "确认订单",
                    "极速付款", "立即付款", "确认付款", "更改付款方式",
                    "查看物流", "再次购买", "评价", "申请售后", "退款成功",
                    "已收货", "已签收", "待发货", "已发货", "待收货",
                    "提交订单", "确认收货",
                    "删除订单", "追加评价", "申请退款", "交易已取消",
                ),
                wechatAlipayExcludeKeywords = listOf(
                    "朋友圈", "通讯录", "发现", "搜索小程序", "扫一扫",
                    "视频号", "看一看", "摇一摇", "附近", "小程序面板",
                ),
                amountRegex = """[¥￥]\s*(\d+\.?\d{0,2})|(\d+\.?\d{0,2})元""",
                cooldownMinutes = 5,
            ),
            sms = SmsRules(
                spamKeywords = listOf(
                    "订购", "退订", "办理", "开通", "激活", "贷款", "借款",
                    "提额", "申请", "审批", "邀请", "回复R", "回复TD",
                    "免费领", "中奖", "恭喜", "点击链接",
                ),
            ),
            sourceMapping = emptyMap(), // sourceMapping fallback to NotificationSourceMapping.KNOWN_PACKAGES
            processor = ProcessorRules(
                scoringWindowSeconds = 10,
                dedupWindowSeconds = 60,
                marketingSuffixCutoffs = listOf(
                    "点击领取", "点击查看", "点击开启", "戳我领", "立即领取",
                    "去领", "快来领", "可领取", "赶紧领",
                ),
                maxAmountCents = 10_000_000,
                minAmountCents = 1,
            ),
        )
    }
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
