package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockAction
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrefilterStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 归表键对齐护栏（服务端 `docs/API.md` 附录 C §11.2）。
 *
 * ## 为什么必须有这条护栏
 *
 * 每个拦截点用 [PrefilterStats.clientOnlyKey] / [CaptureFunnelStats.reportKey] 造出一个
 * `rule_id`，再拿它查云控 `prefilter_policy`。服务端 `rules.json` 里那张归表用的也是同一套 id。
 * **任一端改名/新增枚举，而另一端没跟上，就会静默漂移**：服务端给的键在客户端查不到 →
 * 退回缺省 BLOCK，`forward`/`sample` 静默失效。因为两端各是一份仓库、各自 CI，
 * 谁也跑不到对方的编译期，所以必须在客户端这边把「我能产出哪些 id」钉死，
 * 并断言「服务端真载荷里的归表键必须是我认识的」。
 *
 * ## 三条断言
 *
 * 1. 客户端能产出的全部 `rule_id` **集合逐项等于**一份钉死的清单，且都在 `client_only.` 命名空间
 *    （新增/改名枚举而没更新清单 → 变红，逼人想一下归表键要不要跟着改）；
 * 2. 服务端真载荷归表的键**必须 ∈** 客户端 id 集合（服务端写了客户端不认识的键 → 立刻发现）。
 *    反向不要求：客户端 id 不必都出现在归表里，缺省 = block；
 *    ⚠️ `precheck.*` 命名空间除外：那些 id 由服务端自定义、客户端按原 `rule_id` 逐字查表，
 *    因此「能认识」但**无法在客户端枚举**（不是漏枚举）。断言 ② 对它们只验命名空间，不逐字比对；
 * 3. [BlockAction.fromWire] 遇到未知值返回 null（= 视作 BLOCK，绝不因写错而放行）。
 *
 * **纯单测、零 IO 之外只读一份夹具**；不改产品行为、不碰 rules.json、不动 AIBILL。
 */
class PrefilterPolicyKeyAlignmentTest {

    // ── 客户端能产出的全部 rule_id ──────────────────────────────────────
    // 预筛 13 个 BlockReason → `client_only.<枚举名>`（纯机械拼接，无映射表）
    private val prefilterRuleIds: Set<String> =
        BlockReason.entries.map(PrefilterStats.Companion::clientOnlyKey).toSet()

    // 捕获层 17 个 CaptureDrop → `client_only.capture_dropped_<渠道>_<slug>`
    // （droppedStage → reportKey 的机械派生；reportKey 的可上报性有断言兜底）
    private val captureRuleIds: Set<String> =
        CaptureDrop.entries.map { drop ->
            val stage = CaptureFunnelStats.droppedStage(drop)
            assertTrue(
                PrefilterReport.isReportableRuleId(CaptureFunnelStats.reportKey(stage)),
                "$drop 的上报键不在 precheck.*/client_only.* 命名空间，服务端会 400",
            )
            CaptureFunnelStats.reportKey(stage)
        }.toSet()

    private val clientRuleIds: Set<String> = prefilterRuleIds + captureRuleIds

    /**
     * **钉死的清单**：客户端查表用的全部 rule_id。
     *
     * 新增/改名 BlockReason 或 CaptureDrop 而没更新这份清单 → 断言 ① 变红。
     * 那时不是改清单了事，而是先确认：服务端归表（rules.json）里对应键是否也要跟着改，
     * 否则新判据一上来就是「查不到 → 退回 block」，`forward`/`sample` 对它永不生效。
     */
    private val expectedClientRuleIds: Set<String> = setOf(
        // 预筛（BlockReason，13 个）
        "client_only.SELF_NOTIFICATION",
        "client_only.DUPLICATE_CONTENT",
        "client_only.BLANK_TEXT",
        "client_only.SYSTEM_GROUP_SUMMARY",
        "client_only.SUMMARY_REPORT",
        "client_only.PROMO_SENTENCE",
        "client_only.MARKETING",
        "client_only.NOT_FINANCIAL",
        "client_only.LIST_PAGE",
        "client_only.AI_MEMORY",
        "client_only.AI_MUTED",
        "client_only.DB_DUPLICATE",
        "client_only.PRECHECK",
        // 捕获层（CaptureDrop，17 个）
        "client_only.capture_dropped_nls_no_package",
        "client_only.capture_dropped_nls_no_extras",
        "client_only.capture_dropped_sms_not_action",
        "client_only.capture_dropped_sms_no_messages",
        "client_only.capture_dropped_sms_blank_text",
        "client_only.capture_dropped_a11y_not_whitelisted",
        "client_only.capture_dropped_a11y_no_root",
        "client_only.capture_dropped_a11y_root_mismatch",
        "client_only.capture_dropped_a11y_no_activity",
        "client_only.capture_dropped_a11y_not_payment_activity",
        "client_only.capture_dropped_a11y_no_page_token",
        "client_only.capture_dropped_a11y_no_success_keyword",
        "client_only.capture_dropped_a11y_excluded",
        "client_only.capture_dropped_a11y_bad_amount",
        "client_only.capture_dropped_a11y_history_date",
        "client_only.capture_dropped_a11y_debounce",
        "client_only.capture_dropped_a11y_page_dedup",
    )

    // ────────────────────────── 断言 ① ──────────────────────────

    @Test
    @DisplayName("客户端能产出的全部 rule_id 与钉死清单逐项相等，且都在 client_only. 命名空间")
    fun `客户端 rule_id 集合与清单逐项相等`() {
        assertEquals(
            expectedClientRuleIds,
            clientRuleIds,
            "客户端 rule_id 集合变了。新增/改名枚举会让归表键静默漂移（查不到 → 退回 block）。" +
                "确认服务端 rules.json 的归表要不要跟着改，再更新这份清单。",
        )
        assertTrue(prefilterRuleIds.size == BlockReason.entries.size, "预筛键数应与 BlockReason 枚举数一致")
        assertTrue(captureRuleIds.size == CaptureDrop.entries.size, "捕获层键数应与 CaptureDrop 枚举数一致")
        for (id in clientRuleIds) {
            assertTrue(id.startsWith(PrefilterStats.CLIENT_ONLY_PREFIX), "键不在 client_only. 命名空间：$id")
        }
    }

    // ────────────────────────── 断言 ② ──────────────────────────

    @Test
    @DisplayName("服务端真载荷归表的每个键都 ∈ 客户端 rule_id 集合（服务端写了不认识的键立刻发现）")
    fun `服务端归表键都在客户端 id 集合内`() {
        val serverKeys = NotificationRulesRealPayload.rules().prefilterPolicy.keys
        assertTrue(serverKeys.isNotEmpty(), "真载荷缺 prefilter_policy，服务端应已下发归表")

        // 1) 先验命名空间：`precheck.*` / `client_only.*` 之外的键连 ⑤ 上报都会被 400，更不该进归表
        for (id in serverKeys) {
            assertTrue(PrefilterReport.isReportableRuleId(id), "归表键不在契约命名空间：$id")
        }

        // 2) 再验逐字归属：`client_only.*` 必须逐字命中客户端闭集；`precheck.*` 是服务端自定义 id，
        //    客户端按原名查表（见 PrefilterPolicyGateTest「precheck 查表」），不在闭集里属正常，放行。
        val unknown = serverKeys
            .filterNot { it.startsWith("precheck.") }
            .filterNot { it in clientRuleIds }
        assertTrue(
            unknown.isEmpty(),
            "服务端归表里有客户端不认识的键（客户端查不到 → 退回 BLOCK，forward/sample 对其失效）：" +
                "$unknown\n" +
                "若是客户端漏了枚举：在这里补 BlockReason/CaptureDrop 并更新钉死清单；" +
                "若是服务端键写错：服务端改 rules.json。",
        )
    }

    @Test
    @DisplayName("precheck.* 键即使不在客户端闭集里也不会被误报为陌生键")
    fun `precheck 键不误报为陌生键`() {
        // 服务端可以按契约把 `precheck.<reason>` 写进归表；客户端按原 id 逐字查表，
        // 但这份 id 由服务端定义、客户端无法枚举 → 断言 ② 必须放过它们。
        // 钉住：将来谁给断言 ② 加码（去掉 precheck 豁免）会在这里变红，
        // 而不是等到服务端真下发一条 precheck 归表时误报。
        val precheckIds = listOf("precheck.strong_noise", "precheck.no_signal")
        for (id in precheckIds) {
            assertTrue(PrefilterReport.isReportableRuleId(id), "precheck 键必须满足契约命名空间：$id")
            assertFalse(
                id in clientRuleIds,
                "precheck id 本就不在客户端闭集（服务端自定义），否则断言 ② 的豁免就没意义了：$id",
            )
        }
    }

    @Test
    @DisplayName("反向不要求：客户端 id 不必都出现在归表里（缺省 = block），且反例可构造")
    fun `反向不要求且可构造反例`() {
        // 归表只写 1 条时，其余 id 仍是合法的「缺省 block」——不报错
        val tiny = mapOf("client_only.MARKETING" to "forward")
        val unknown = tiny.keys.filterNot { it in clientRuleIds }
        assertTrue(unknown.isEmpty())
        assertFalse(
            tiny.keys.containsAll(clientRuleIds),
            "只写 1 条时不该覆盖全部 id，否则这条反例就失去意义",
        )
    }

    // ────────────────────────── 断言 ③ ──────────────────────────

    @Test
    @DisplayName("BlockAction.fromWire 未知值返回 null（= 视作 BLOCK，写错不放行）")
    fun `未知值返回 null`() {
        // 三个合法值逐字映射
        assertEquals(BlockAction.BLOCK, BlockAction.fromWire("block"))
        assertEquals(BlockAction.FORWARD, BlockAction.fromWire("forward"))
        assertEquals(BlockAction.SAMPLE, BlockAction.fromWire("sample"))
        // 前后空白容忍（trim）
        assertEquals(BlockAction.FORWARD, BlockAction.fromWire(" forward "))

        // 未知值 / 拼写 / 大小写 / 空值一律 null（null → 缺省 BLOCK）
        assertNull(BlockAction.fromWire(null), "null 必须是 BLOCK，不是放行")
        assertNull(BlockAction.fromWire(""), "空串必须是 BLOCK")
        assertNull(BlockAction.fromWire("drop"), "拼写错必须是 BLOCK")
        assertNull(BlockAction.fromWire("BLOCK"), "大小写必须严格，否则与服务端校验口径分叉")
        assertNull(BlockAction.fromWire("Forward"), "同上，大写 F 也必须 BLOCK")
        assertNull(BlockAction.fromWire("samplee"), "多打一个字母必须是 BLOCK")
        assertNull(BlockAction.fromWire("1"), "数字不是合法值")
    }
}
