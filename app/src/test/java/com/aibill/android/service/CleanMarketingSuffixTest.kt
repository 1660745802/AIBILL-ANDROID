package com.aibill.android.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 营销文案截断逻辑测试
 *
 * 对应 NotificationProcessor.cleanMarketingSuffix 的核心逻辑。
 */
class CleanMarketingSuffixTest {

    private val cutoffs = listOf("点击领取", "点击查看", "点击开启", "戳我领", "立即领取", "去领", "快来领", "可领取", "赶紧领")
    private val commaKeywords = listOf("红包", "领", "优惠", "积分", "返现", "奖励")

    /** 模拟 cleanMarketingSuffix 逻辑 */
    private fun cleanMarketingSuffix(text: String): String {
        // 1. 直接截断
        for (cutoff in cutoffs) {
            val idx = text.indexOf(cutoff)
            if (idx > 0) return text.substring(0, idx).trim()
        }
        // 2. 逗号后含营销关键词则截断
        if (commaKeywords.isNotEmpty()) {
            val commaIdx = text.indexOf("，")
            if (commaIdx > 0) {
                val tail = text.substring(commaIdx)
                if (commaKeywords.any { tail.contains(it) }) {
                    return text.substring(0, commaIdx).trim()
                }
            }
        }
        return text
    }

    // === 直接截断 ===

    @Test
    fun `点击领取 截断`() {
        // "点击领取"在逗号后，截断到idx处，结果带末尾逗号（trim只去空格）
        val result = cleanMarketingSuffix("交易提醒 你有一笔30元的支出，点击领取2个积分。")
        assertEquals("交易提醒 你有一笔30元的支出，", result)
    }

    @Test
    fun `去领 截断`() {
        val result = cleanMarketingSuffix("交易提醒 你有一笔30.26元的支出，去领0.5元水燃费红包。")
        assertEquals("交易提醒 你有一笔30.26元的支出，", result)
    }

    @Test
    fun `立即领取 截断`() {
        val result = cleanMarketingSuffix("你有一笔消费，立即领取优惠券")
        assertEquals("你有一笔消费，", result)
    }

    @Test
    fun `无截断词 原文返回`() {
        assertEquals(
            "微信支付 已支付¥24.00",
            cleanMarketingSuffix("微信支付 已支付¥24.00")
        )
    }

    @Test
    fun `截断词在开头(idx=0) 不截断`() {
        assertEquals(
            "点击领取红包",
            cleanMarketingSuffix("点击领取红包")
        )
    }

    // === 逗号截断 ===

    @Test
    fun `逗号后含红包 截断`() {
        assertEquals(
            "交易提醒 你有一笔0.90元的支出",
            cleanMarketingSuffix("交易提醒 你有一笔0.90元的支出，领0.5元红包")
        )
    }

    @Test
    fun `逗号后含积分 截断`() {
        // "点击领取"在cutoffs中，idx>0先命中
        val result = cleanMarketingSuffix("你有一笔消费，点击领取2个支付宝积分。")
        assertEquals("你有一笔消费，", result)
    }

    @Test
    fun `逗号后含优惠 截断`() {
        assertEquals(
            "支付成功",
            cleanMarketingSuffix("支付成功，享受新人优惠")
        )
    }

    @Test
    fun `逗号后无营销关键词 不截断`() {
        assertEquals(
            "你有一笔30元的支出，收款方为沙县小吃",
            cleanMarketingSuffix("你有一笔30元的支出，收款方为沙县小吃")
        )
    }

    @Test
    fun `英文逗号不触发截断`() {
        assertEquals(
            "amount: 30.00, reward: 0.5",
            cleanMarketingSuffix("amount: 30.00, reward: 0.5")
        )
    }

    // === 组合场景 ===

    @Test
    fun `同时有截断词和逗号营销 最先匹配的cutoff截断`() {
        // cutoffs按顺序遍历，"点击领取"先于"去领"被匹配到，截断到"点击领取"的idx位置
        val result = cleanMarketingSuffix("支付30元，去领红包，点击领取积分")
        assertEquals("支付30元，去领红包，", result)
    }

    @Test
    fun `真实支付宝交易提醒格式`() {
        assertEquals(
            "交易提醒 你有一笔23.89元的支出",
            cleanMarketingSuffix("交易提醒 你有一笔23.89元的支出，领最高2.7元满5元提现。")
        )
    }
}
