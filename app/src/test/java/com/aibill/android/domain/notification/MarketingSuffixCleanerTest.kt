package com.aibill.android.domain.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 营销后缀清洗（预筛 precheck 与 AI `input` 共用的纯函数）。
 *
 * 行为逐字保留自 `NotificationInterpreterImpl.cleanMarketingSuffix`：
 * 截断要求 `idx > 0`（开头命中不截，避免把整条消息清成空），结果 `trim`。
 */
class MarketingSuffixCleanerTest {

    @Test
    @DisplayName("suffix_cutoffs 命中处截断 + trim")
    fun suffixCutoffTruncates() {
        assertEquals(
            "支付宝交易提醒 支出20.00元",
            MarketingSuffixCleaner.clean("支付宝交易提醒 支出20.00元 点击领取红包", listOf("点击领取"), emptyList()),
        )
    }

    @Test
    @DisplayName("命中在开头（idx==0）不截断——否则整条消息会被清空")
    fun cutoffAtStartIsIgnored() {
        val text = "点击领取：您的付款失败"
        assertEquals(text, MarketingSuffixCleaner.clean(text, listOf("点击领取"), emptyList()))
    }

    @Test
    @DisplayName("suffix_cutoffs 未命中时回落到 comma_keywords；都不命中则原样返回")
    fun fallsBackToCommaKeywords() {
        assertEquals(
            "消费32.50元 再享",
            MarketingSuffixCleaner.clean("消费32.50元 再享红包", emptyList(), listOf("红包")),
        )
        assertEquals("消费32.50元", MarketingSuffixCleaner.clean("消费32.50元", listOf("XX"), listOf("红包")))
    }

    @Test
    @DisplayName("换行分隔的营销文案也能截断（逗号不是唯一分隔符）")
    fun newlineSeparatedMarketing() {
        val text = "12包抽纸0元领千份放量！\n实付159元再享40元红包返利>"
        assertEquals("12包抽纸0元领千份放量！", MarketingSuffixCleaner.clean(text, emptyList(), listOf("实付")))
    }
}
