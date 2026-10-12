package com.aibill.android.domain.notification

/**
 * 营销后缀清洗：把通知正文里「真实交易信息 + 点击领取XX」的营销尾巴截掉。
 *
 * ## 为什么必须两边用同一个串
 *
 * 转发给 `/api/ai/parse` 的 `input` 是清洗后的文本（[NotificationInterpreter] 调用）。
 * 云控 `precheck.bare_amount` 的长度阈值（`len<=30`）服务端是按**这个 `input`** 判的；
 * 若预筛拿**未清洗的全文**去算，30 字边界会与两端计数口径分叉。
 * 所以清洗逻辑抽成纯函数，[com.aibill.android.data.notification.NotificationPrefilterImpl]
 * 与解释器共用，保证预筛看到的串与请求里的 `input` 逐字一致。
 *
 * 纯函数、无状态：两个调用点传各自的词表（都来自云控 `processor`），行为仅由入参决定。
 */
object MarketingSuffixCleaner {

    /**
     * @param suffixCutoffs `processor.marketing_suffix_cutoffs`：命中处**截断**（要求 `idx > 0`）
     * @param commaKeywords `processor.marketing_comma_keywords`：营销词首次出现处截断（同样要求 `idx > 0`）
     */
    fun clean(
        text: String,
        suffixCutoffs: List<String>,
        commaKeywords: List<String>,
    ): String {
        for (cutoff in suffixCutoffs) {
            val idx = text.indexOf(cutoff)
            if (idx > 0) return text.substring(0, idx).trim()
        }
        for (keyword in commaKeywords) {
            val idx = text.indexOf(keyword)
            if (idx > 0) return text.substring(0, idx).trim()
        }
        return text
    }
}
