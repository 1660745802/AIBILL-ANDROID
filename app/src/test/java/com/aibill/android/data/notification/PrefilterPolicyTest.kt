package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockAction
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.PrefilterPolicy
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrefilterStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 云控归表 `prefilter_policy` 的派生（跨端契约 docs/API.md 附录 C §11.2）。
 *
 * 这一层只回答「表怎么读」：**缺键 = BLOCK**（= 今天的行为），非法值/非法命名空间
 * 一律**不入表**（也是 BLOCK）并 warn。查表后的行为（forward 继续链 / sample 采样）
 * 由 `PrefilterPolicyGateTest` 与 `CaptureSamplerTest` 分别钉住。
 *
 * 最后一条用例直接读**服务端发布载荷真副本**：服务端 `validateRules` 只校验「格式是
 * `client_only.*` + 三值之一」，「是否属于已知客户端判据」由服务端闭集测试钉；
 * 客户端这边要保证的是**不静默丢条目**——真载荷里每一条都必须进表，
 * 否则会出现「运维改了归表、客户端当没看见」这种最难查的故障。
 */
class PrefilterPolicyTest {

    private fun snapshotPolicy(policy: Map<String, String>): PrefilterPolicy =
        InMemoryNotificationRuleSource(PrefilterTestHarness.rulesWithPolicy(policy))
            .snapshot()
            .prefilterPolicy

    @Test
    @DisplayName("缺键/空表 → 全部 BLOCK（旧快照/旧载荷零变化）")
    fun `空表全部 BLOCK`() {
        val empty = snapshotPolicy(emptyMap())
        assertTrue(empty.isEmpty, empty.toString())
        // 任意 rule_id（含云控 precheck.*）都退化成 BLOCK
        assertEquals(BlockAction.BLOCK, empty.actionFor(PrefilterStats.clientOnlyKey(BlockReason.MARKETING)))
        assertEquals(BlockAction.BLOCK, empty.actionFor("precheck.strong_noise"))
        assertEquals(BlockAction.BLOCK, PrefilterPolicy.EMPTY.actionFor("client_only.does_not_exist"))
    }

    @Test
    @DisplayName("三个合法值逐字映射；未列出的键仍是 BLOCK")
    fun `合法值映射`() {
        val policy = snapshotPolicy(
            mapOf(
                "client_only.MARKETING" to "forward",
                "client_only.capture_dropped_a11y_excluded" to "sample",
                "client_only.LIST_PAGE" to "block",
            )
        )
        assertEquals(BlockAction.FORWARD, policy.actionFor("client_only.MARKETING"))
        assertEquals(BlockAction.SAMPLE, policy.actionFor("client_only.capture_dropped_a11y_excluded"))
        assertEquals(BlockAction.BLOCK, policy.actionFor("client_only.LIST_PAGE"))
        assertEquals(BlockAction.BLOCK, policy.actionFor("client_only.SUMMARY_REPORT"))
        assertEquals(3, policy.actions.size)
    }

    @Test
    @DisplayName("值非法（大小写/拼错/空）→ 不入表 = BLOCK，不影响同一张表的其它条目")
    fun `非法值按 BLOCK`() {
        val policy = snapshotPolicy(
            mapOf(
                "client_only.MARKETING" to "Forward",   // 大写：实际云控是 lowercase，非契约值
                "client_only.LIST_PAGE" to "drop",      // 拼错（服务端 validateRules 也会拒）
                "client_only.PROMO_SENTENCE" to "",
                "client_only.SUMMARY_REPORT" to "sample",   // 合法：必须还在
            )
        )
        assertEquals(BlockAction.BLOCK, policy.actionFor("client_only.MARKETING"))
        assertEquals(BlockAction.BLOCK, policy.actionFor("client_only.LIST_PAGE"))
        assertEquals(BlockAction.BLOCK, policy.actionFor("client_only.PROMO_SENTENCE"))
        assertEquals(BlockAction.SAMPLE, policy.actionFor("client_only.SUMMARY_REPORT"))
        assertEquals(1, policy.actions.size)
    }

    @Test
    @DisplayName("键不在契约命名空间 → 忽略（永远匹配不上拦截点，留着只会让人以为生效）")
    fun `非法键忽略`() {
        val policy = snapshotPolicy(
            mapOf(
                "other.SUMMARY_REPORT" to "forward",
                "client_onlyx.MARKETING" to "forward",
                "client_only.capture_dropped_a11y_excluded" to "sample",
            )
        )
        assertFalse(policy.actions.containsKey("other.SUMMARY_REPORT"))
        assertFalse(policy.actions.containsKey("client_onlyx.MARKETING"))
        assertEquals(1, policy.actions.size)
    }

    @Test
    @DisplayName("真载荷（服务端发布副本）里每一条归表都进表，且键都在契约命名空间")
    fun `真载荷归表不丢条目`() {
        val raw = NotificationRulesRealPayload.rules().prefilterPolicy
        assertTrue(raw.isNotEmpty(), "服务端真载荷应带 prefilter_policy（§11.2 已落 rules.json）")
        for (key in raw.keys) {
            assertTrue(PrefilterReport.isReportableRuleId(key), "归表键不在契约命名空间：$key")
        }
        val snapshot = InMemoryNotificationRuleSource(NotificationRulesRealPayload.rules()).snapshot()
        assertEquals(
            raw.size,
            snapshot.prefilterPolicy.actions.size,
            "真载荷有条目被静默丢弃：raw=${raw.keys} compiled=${snapshot.prefilterPolicy.actions.keys}",
        )
        // 且没有任何一条被降级成 BLOCK（值合法 = 原样生效）
        for ((key, value) in raw) {
            assertEquals(BlockAction.fromWire(value), snapshot.actionFor(key), "归表条目未生效：$key=$value")
        }
    }
}
