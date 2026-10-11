package com.aibill.android.domain.notification

/**
 * 营销词判定：命中词表 **且** 不含强交易特征才算营销。
 *
 * ## 为什么单独成一处
 *
 * 这段判据此前以 `NotificationRulesManager.isMarketingByWords` 的形式挂在
 * `service` 包的 companion 上，两处判据（`default_rule` 分支、`per_package` 分支）
 * 与 NLS/SMS 各自的拦截都从那里取「同源」结论，但回归测试只能通过反射式引用它——
 * 判据与词表拧在一起，任何一处改语义都要靠 review 抓。
 * 判据属于 domain，词表属于云控快照，两者分开后「同一套判据」才是可断言的事实。
 *
 * ## 设计原则：极保守，宁可多放不漏
 *
 * 形似真实交易（尾号/卡号/入账…）的文本即使含「办理/开通/贷款」等词，也交由 AI 判定，
 * 避免误杀 `您尾号1234信用卡办理的分期入账3500元` 这类真实交易。
 */
object MarketingCriteria {

    /**
     * 强交易特征词：命中任一即视为「形似真实交易」，营销词不再一票否决。
     * 故意不含「到账/收入」（营销文案爱用「额度已到账」）。
     */
    private val STRONG_TXN_MARKERS = listOf(
        "尾号", "卡号", "账户", "储蓄卡", "信用卡", "入账", "支出", "消费",
        "扣款", "转账", "汇款", "还款", "余额",
    )

    /**
     * 按**指定词表**判定是否营销。
     *
     * 词表由调用方传入：`default_rule` 分支传 `sms.spam_keywords + exclude_content_contains`，
     * `per_package` 分支只传该包自己的 `exclude_content_contains`——两者语义必须一致，
     * 否则会出现「营销词 + 真交易」在两条路径上给出相反结论（2026-10-01 真实漏记）。
     */
    fun isMarketingByWords(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        if (words.none { text.contains(it) }) return false
        return STRONG_TXN_MARKERS.none { text.contains(it) }
    }
}
