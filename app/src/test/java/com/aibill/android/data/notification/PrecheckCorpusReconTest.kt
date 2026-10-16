package com.aibill.android.data.notification

import com.aibill.android.domain.notification.MarketingSuffixCleaner
import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **21 条语料逐条对账**：客户端（真载荷编译出的判据）vs 服务端 dump
 * `v15-corpus-server-verdict.json`（`server/src/ai/verify.ts :: classifyNotification`）。
 *
 * ## 为什么用真载荷
 *
 * 判据来自 [PrecheckRealPayload]（服务端真实路由导出 + 生产编译路径），语料来自服务端 dump：
 * 两端同一 `corpus_hash` / `content_hash`，任一近似夹具都可能像 8694c09 那次一样把真形状掩盖掉。
 *
 * ## 判据入口与输入串
 *
 * 生产入口是 [PrecheckJudge.evaluate]（预筛链第 11 步实际调用的就是它）。输入串与转发给
 * AI 的 `input` 同口径：语料无营销后缀，[MarketingSuffixCleaner] 在空词表下是恒等变换，
 * 这里仍显式走一遍，避免将来给语料加营销尾巴时对账口径分叉。
 *
 * ## 断言分级（P0 硬红，其余只报告）
 *
 * - **硬红**：`should_call_ai=true` 却被客户端拦（含 `expect_txn=true` 的 **P0 误杀**）；
 *   服务端要拦、客户端也拦但 `rule_id` 不符（⑤ 的计数键就是它，不符就是上报错账）。
 * - **软差异（可接受，写入报告）**：漏挡（服务端拦、客户端放行）与「同放行但 rule_id 等级不同」
 *   的口径差——见 `recon-result.md`。[KNOWN_SOFT_DIFF_IDS] 只能是「已确认可接受」的名单：
 *   目前为空 → **任何**软差异都会直接红（防差异静默漂移）。
 *
 * 报告写到 `<module>/build/reports/precheck-recon/recon-result.md`（CI 安全，不写死绝对路径）；
 * 给人看的那份由主 agent 拷到 `Downloads/x/recon-result.md`。
 */
class PrecheckCorpusReconTest {

    private val logger = AppLogger(mockk(relaxed = true))

    private data class ServerCase(
        val id: String,
        val channel: String?,
        val text: String,
        val expectTier: Int,
        val expectedRuleId: String,
        val expectTxn: Boolean,
        val shouldCallAi: Boolean,
    )

    private data class Row(
        val case: ServerCase,
        val clientRuleId: String?,
        val clientBlocked: Boolean,
    )
    private enum class Severity(val label: String) {
        P0_FALSE_KILL("误杀 P0（expect_txn=true 却被拦，丢真账）"),
        OVER_AGGRESSIVE("超判（服务端要放行，客户端拦）"),
        BLOCK_ID_MISMATCH("拦截 rule_id 不符（⑤ 计数键错）"),
        MISSED_BLOCK("漏挡（服务端拦、客户端放行；可接受）"),
        PASS_ID_QUOTA("放行 rule_id 口径差（同放行、等级不同；可接受）"),
    }

    @Test
    @DisplayName("21 条语料：客户端判决逐条对账服务端 dump（P0 不误杀 / 拦截 rule_id 逐字相符）")
    fun reconcileAgainstServerDump() {
        val rules = PrecheckRealPayload.compileRules(logger)
        // 前置：真载荷可用且就是那 15 条（对账本身若跑在残缺链上会得到假结论）
        assertTrue(rules.available, "真载荷必须编译出可用判据链")
        assertEquals(15, rules.rules.size)

        val cases = loadServerCases()
        assertEquals(21, cases.size, "服务端 dump 语料条数变了，请同步夹具与 hash")

        // ⚠️ `manual` 语料不属于客户端：预筛只在 nls/sms/a11y 三个入口跑，
        // 客户端**不存在**「手打短句」这个入口（那是服务端 App 自己的来源）。
        // 它们仍要保留计数并写进报告（不能静默消失），但不参与判决对账。
        val manual = cases.filter { clientChannel(it) == null }
        val rows = cases.mapNotNull { case ->
            val channel = clientChannel(case) ?: return@mapNotNull null
            evaluate(case, rules, channel)
        }
        assertEquals(
            cases.size - manual.size,
            rows.size,
            "除 manual 外每条语料都必须能被某个客户端入口求值；不该有第三条路",
        )

        val reportFile = writeReport(renderReport(rows, manual))

        val classified = rows.map { it to classify(it) }
        val hard = classified.filter { (_, cats) -> cats.any { it in HARD } }
        val soft = classified.filter { (_, cats) -> cats.isNotEmpty() && cats.none { it in HARD } }

        val hardTable = renderTable(hard.map { it.first })
        assertTrue(
            hard.isEmpty(),
            "客户端判决与服务端 dump 存在**硬差异**（P0 误杀 / 超判 / 拦截 rule_id 不符）：\n" +
                "$hardTable\n完整报告: ${reportFile.absolutePath}",
        )

        val unexpectedSoft = soft.map { it.first.case.id }.filterNot { it in KNOWN_SOFT_DIFF_IDS }
        val softTable = renderTable(soft.map { it.first })
        assertTrue(
            unexpectedSoft.isEmpty(),
            "出现**未登记的新软差异**（请在 recon-result.md 里登记并同步服务端）：$unexpectedSoft\n$softTable",
        )

        // 双向 hash 自证：真载荷的语料 hash 必须与服务端 dump 的 corpus_hash 逐字相同
        assertEquals(
            PrecheckRealPayload.corpusHash(),
            serverCorpusHash(),
            "两边语料 hash 不一致 → 对账前提不成立（夹具拿错了版本）",
        )
    }

    /**
     * 客户端首命中规则 id。
     *
     * [PrecheckJudge.evaluate] 的 `Pass` 不带 ruleId（生产只要「拦不拦」，不需要 reason），
     * 所以这里把**同一条链的 tier 全改成 0** 再用生产 judge 跑一次：matcher / signals 不变，
     * 首命中必然以 `Blocked(ruleId)` 暴露出来。命中集合与真实链逐字一致，不复制任何匹配逻辑。
     */
    private fun clientMatchedRuleId(input: String, rules: PrecheckRules, channel: SourceChannel): String? {
        val probe = rules.copy(rules = rules.rules.map { it.copy(tier = 0) })
        return (PrecheckJudge.evaluate(input, probe, channel) as? PrecheckVerdict.Blocked)?.ruleId
    }

    private fun evaluate(case: ServerCase, rules: PrecheckRules, channel: SourceChannel): Row {
        // 与预筛链同口径：先过营销后缀清洗（空词表下 = 原文），再喂判据
        val input = MarketingSuffixCleaner.clean(case.text, suffixCutoffs = emptyList(), commaKeywords = emptyList())
        val verdict = PrecheckJudge.evaluate(input, rules, channel)
        return Row(
            case = case,
            clientRuleId = clientMatchedRuleId(input, rules, channel),
            clientBlocked = verdict is PrecheckVerdict.Blocked,
        )
    }

    /**
     * 语料入口 → 客户端入口。**null = 客户端不跑这条**（目前只有 `manual`）。
     *
     * 与服务端 `classifyNotification(rawInput, channel)` 同一份入口名（小写）。
     */
    private fun clientChannel(case: ServerCase): SourceChannel? =
        when (case.channel?.lowercase()) {
            "nls" -> SourceChannel.NLS
            "sms" -> SourceChannel.SMS
            "a11y" -> SourceChannel.A11Y
            else -> null
        }

    private fun classify(row: Row): Set<Severity> = buildSet {
        if (row.case.shouldCallAi && row.clientBlocked) {
            add(if (row.case.expectTxn) Severity.P0_FALSE_KILL else Severity.OVER_AGGRESSIVE)
        }
        if (!row.case.shouldCallAi && !row.clientBlocked) add(Severity.MISSED_BLOCK)
        if (!row.case.shouldCallAi && row.clientBlocked && row.clientRuleId != row.case.expectedRuleId) {
            add(Severity.BLOCK_ID_MISMATCH)
        }
        if (row.case.shouldCallAi && !row.clientBlocked && row.clientRuleId != row.case.expectedRuleId) {
            add(Severity.PASS_ID_QUOTA)
        }
    }

    // ---------------------------------------------------------------------
    // 夹具加载
    // ---------------------------------------------------------------------

    private fun loadServerCases(): List<ServerCase> {
        val root = parseDump()
        @Suppress("UNCHECKED_CAST")
        val list = root["cases"] as? List<Map<String, Any?>> ?: error("服务端 dump 缺 cases")
        return list.map { m ->
            @Suppress("UNCHECKED_CAST")
            val ids = m["precheck_rule_id"] as? List<String> ?: error("用例缺 precheck_rule_id")
            ServerCase(
                id = m["id"] as? String ?: error("用例缺 id"),
                channel = m["channel"] as? String,
                text = m["text"] as? String ?: error("用例缺 text"),
                expectTier = (m["expect_tier"] as? Number)?.toInt() ?: error("用例缺 expect_tier"),
                expectedRuleId = ids.single(),
                expectTxn = m["expect_txn"] as? Boolean ?: false,
                shouldCallAi = m["should_call_ai"] as? Boolean ?: error("用例缺 should_call_ai"),
            )
        }
    }

    private fun serverCorpusHash(): String = parseDump()["corpus_hash"] as? String ?: error("dump 缺 corpus_hash")

    private fun parseDump(): Map<String, Any?> {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val text = TestResources.file(CORPUS_FILE).readText()
        return Moshi.Builder().build().adapter<Map<String, Any?>>(type).fromJson(text)
            ?: error("服务端 dump 解析失败")
    }

    // ---------------------------------------------------------------------
    // 报告
    // ---------------------------------------------------------------------

    private fun writeReport(markdown: String): File {
        val file = File(TestResources.moduleRoot(), "build/reports/precheck-recon/recon-result.md")
        file.parentFile?.mkdirs()
        file.writeText(markdown)
        return file
    }

    private fun renderReport(rows: List<Row>, skippedManual: List<ServerCase>): String {
        val hard = rows.filter { classify(it).any { s -> s in HARD } }
        val soft = rows.filter { classify(it).isNotEmpty() && classify(it).none { s -> s in HARD } }
        val falseKills = hard.count { Severity.P0_FALSE_KILL in classify(it) }
        val missed = soft.count { Severity.MISSED_BLOCK in classify(it) }
        val idQuota = rows.count { Severity.PASS_ID_QUOTA in classify(it) }

        val payloadCorpusHash = PrecheckRealPayload.corpusHash()
        val dumpCorpusHash = serverCorpusHash()
        val sameCorpus = payloadCorpusHash == dumpCorpusHash

        return buildString {
            appendLine("# precheck 对账结果（客户端判据 vs 服务端 dump）")
            appendLine()
            appendLine("> 生成：`PrecheckCorpusReconTest`（可复现：`./gradlew :app:testDebugUnitTest --tests '*PrecheckCorpusReconTest*'`）")
            appendLine()
            appendLine("## 两侧 hash")
            appendLine()
            appendLine("| 来源 | 字段 | 值 |")
            appendLine("|---|---|---|")
            appendLine("| 客户端真实载荷 | `content_hash` | `${PrecheckRealPayload.contentHash()}` |")
            appendLine("| 客户端真实载荷 | `corpus_hash` | `$payloadCorpusHash` |")
            appendLine("| 服务端 dump | `corpus_hash` | `$dumpCorpusHash` |")
            appendLine()
            appendLine(if (sameCorpus) "✅ 两边 `corpus_hash` 逐字相同，对账前提成立。" else "⚠️ 两边 `corpus_hash` 不一致，对账结论无效！")
            appendLine()
            appendLine("## 结论")
            appendLine()
            appendLine("- 语料条数：**${rows.size}**")
            appendLine("- 硬差异（P0 误杀 / 超判 / 拦截 rule_id 不符）：**${hard.size}**")
            appendLine("- P0 误杀（`expect_txn=true` 却被拦）：**$falseKills**")
            appendLine("- 软差异（可接受）：**${soft.size}**（其中漏挡 $missed、放行 rule_id 口径差 $idQuota）")
            appendLine("- **跳过（客户端无此入口）：${skippedManual.size}** —— ${skippedManual.joinToString { it.id }}")
            appendLine(
                "  > `manual`（手打短句）是服务端自己的来源，客户端预筛只跑 nls/sms/a11y，" +
                    "因此不在客户端对账范围；服务端侧仍按同一判据链裁决。",
            )
            if (hard.isEmpty() && soft.isEmpty()) {
                appendLine("- ${rows.size} 条**全部一致**。")
            }
            appendLine()
            appendLine("## 逐条明细")
            appendLine()
            appendLine("| # | case | channel | text | 服务端 rule_id | 客户端 rule_id | should_call_ai | 客户端 | 分级 |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            rows.forEachIndexed { i, row ->
                val cats = classify(row)
                appendLine(
                    "| ${i + 1} | ${row.case.id} | ${row.case.channel ?: "-"} | ${escape(row.case.text)} | " +
                        "${row.case.expectedRuleId} | ${row.clientRuleId ?: "-"} | ${row.case.shouldCallAi} | " +
                        "${if (row.clientBlocked) "Blocked" else "Pass"} | ${severityLabel(cats)} |",
                )
            }
            appendLine()
            if (hard.isNotEmpty()) {
                appendLine("## 硬差异（必须处理）")
                appendLine()
                appendLine(renderTable(hard))
                appendLine()
            }
            if (soft.isNotEmpty()) {
                appendLine("## 软差异（可接受，待服务端判定口径）")
                appendLine()
                appendLine(renderTable(soft))
            }
        }
    }

    private fun renderTable(rows: List<Row>): String {
        if (rows.isEmpty()) return "（无）"
        return buildString {
            appendLine("| case | text | 服务端 rule_id | 客户端 rule_id | 客户端 | 服务端 should_call_ai | expect_txn | 分级 |")
            appendLine("|---|---|---|---|---|---|---|---|")
            rows.forEach { row ->
                appendLine(
                    "| ${row.case.id} | ${escape(row.case.text)} | ${row.case.expectedRuleId} | " +
                        "${row.clientRuleId ?: "-"} | ${if (row.clientBlocked) "Blocked" else "Pass"} | " +
                        "${row.case.shouldCallAi} | ${row.case.expectTxn} | ${severityLabel(classify(row))} |",
                )
            }
        }
    }

    private fun severityLabel(cats: Set<Severity>): String =
        if (cats.isEmpty()) "一致" else cats.joinToString("；") { it.label }

    private fun escape(text: String): String =
        text.replace("|", "\\|").replace("\n", " ").let { if (it.length > 48) it.take(48) + "…" else it }

    private companion object {
        const val CORPUS_FILE = "v15-corpus-server-verdict.json"

        /** P0 级：任何一条都必须立刻处理。 */
        val HARD = setOf(Severity.P0_FALSE_KILL, Severity.OVER_AGGRESSIVE, Severity.BLOCK_ID_MISMATCH)

        /**
         * 已知软差异名单。**当前为空**：服务端让 `money` pattern 自足千分位
         * （content_hash `4a74ba0a…`）后，`sms-bank-consume-thousands` 的 tier1/tier3
         * 口径差已消失（两端都 `precheck.money+verb`），故名单清空。
         * 只有「已确认可接受」的差异才允许登记到这里。
         */
        val KNOWN_SOFT_DIFF_IDS = emptySet<String>()
    }
}
