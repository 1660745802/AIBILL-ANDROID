package com.aibill.android.service

import com.aibill.android.data.notification.InMemoryNotificationRuleSource
import com.aibill.android.data.notification.PrefilterTestHarness
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * 跨端语料护栏：**客户端预筛不得误杀服务端认定的真交易**。
 *
 * ## 语料来源与 hash
 *
 * `app/src/test/resources/ai-prefilter-corpus.json` 是从服务端 `server/config/rules.json`
 * 顶层 `testcases` vendor 来的**测试夹具**（非运行时真源；服务端发布时会剔除 testcases，
 * 因为把它塞进下发 payload 会让「改一条测试用例」就换 content_hash → 全体客户端重拉）。
 * 夹具里带 `_corpus_hash`，服务端改语料会主动播报新 hash——更新夹具时请一并核对。
 *
 * ## 这里只断言「不误杀」这一半（P0），不追求 reason 对齐
 *
 * 契约 §10 定的是**不对称**判据：
 * - 夹具标了「必须放行」的（`expect_txn=true` **或** `expect_client=pass`）而客户端拦了
 *   = **误杀**（丢真账，P0）→ 必须红；
 * - 其余（噪声）被放行 = **漏挡**（可接受，计入上报）→ 不断言。
 *
 * ⚠️ 不要用 `tier ∈ {1,2,3}` 当「真交易」的代理：a11y 的「待付款页/零钱余额页」也会落 tier1，
 *    那是服务端已知问题（动词表含充值/提现），把它们要求成 pass 会写出假护栏。
 *
 * 两边 reason **不是同一套分类学**（客户端是判据链的 BlockReason，服务端是 classifyNotification
 * 的 tier/reason），所以这里**不去断言 `expect_client` 里那些服务端 reason id**——那需要在
 * precheck 下发（契约 §8 ④）之后才谈得上；在 ④ 之前硬对齐只会造出第三种口径。
 *
 * `channel == "manual"` 的用例**跳过**：手工记账不经过预筛（直接调 AI），拿它断言预筛是错的。
 */
class AiPrefilterCorpusGuardTest {

    private val prefilter = PrefilterTestHarness.newPrefilterWithSource(
        InMemoryNotificationRuleSource(PrefilterTestHarness.rules()),
    )

    private data class Case(
        val id: String,
        val channel: String?,
        val text: String,
        val expectTier: Int?,
        /** 夹具意图标记：true = 真交易。**P0 断言挂这里，不用 tier 当代理**
         *  （a11y 的「待付款页/零钱余额页」也会落 tier1，却不是交易）。 */
        val expectTxn: Boolean?,
        /** 只允许 `pass` 或缺省（缺省 = 不断言）；block:* 写法服务端已拒绝。 */
        val expectClient: String?,
    )

    private fun loadCases(): List<Case> {
        val file = listOf(
            File("src/test/resources/ai-prefilter-corpus.json"),
            File("app/src/test/resources/ai-prefilter-corpus.json"),
        ).firstOrNull { it.exists() } ?: error("找不到语料夹具（工作目录=${File(".").absolutePath}）")

        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val root = Moshi.Builder().build().adapter<Map<String, Any>>(type).fromJson(file.readText())
            ?: error("语料夹具解析失败")
        @Suppress("UNCHECKED_CAST")
        val list = root["testcases"] as? List<Map<String, Any?>> ?: error("夹具缺 testcases")

        return list.map { m ->
            Case(
                id = m["id"] as? String ?: "?",
                channel = m["channel"] as? String,
                text = m["text"] as? String ?: error("用例缺 text"),
                expectTier = (m["expect_tier"] as? Number)?.toInt(),
                expectTxn = m["expect_txn"] as? Boolean,
                expectClient = m["expect_client"] as? String,
            )
        }
    }

    @Test
    @DisplayName("真交易（expect_txn=true / expect_client=pass）客户端预筛一条都不许拦")
    fun neverBlocksRealTransactions() = runTest {
        val cases = loadCases()
        assertTrue(cases.size >= 20, "语料条数异常：${cases.size}")

        val falseKills = mutableListOf<String>()
        val checked = mutableListOf<String>()

        for (c in cases) {
            if (c.channel == "manual") continue          // 手工链路不过预筛
            // 断言条件：夹具要求「必须放行」的两类标记
            val mustPass = c.expectClient == "pass" || c.expectTxn == true
            if (!mustPass) continue
            checked += c.id

            val verdict = prefilter.evaluate(
                Capture(
                    channel = when (c.channel) {
                        "sms" -> SourceChannel.SMS
                        "a11y" -> SourceChannel.A11Y
                        else -> SourceChannel.NLS
                    },
                    packageName = packageOf(c.text),
                    title = "",
                    fullText = c.text,
                    receivedAt = 1_700_000_000_000L,
                )
            )
            if (verdict is Verdict.Block) {
                falseKills += "${c.id}(ch=${c.channel}, tier=${c.expectTier}, reason=${verdict.reason})"
            }
        }

        assertTrue(checked.isNotEmpty(), "没有任何可断言的用例")
        assertTrue(
            falseKills.isEmpty(),
            "客户端预筛误杀了真交易（P0，丢真账）：$falseKills\n" +
                "（已检查 ${checked.size} 条；语料 hash 见夹具 _corpus_hash）",
        )
    }

    /**
     * 包名推断：语料只给文本，而判据链按包名分流。
     *
     * 只认「文本里点名了的 App」，其余走**未知包名**——那正是通用判据（支付信号/词表）生效的路径，
     * 也是误杀风险的所在。刻意不做「sms 一律当银行」这类猜测：那会把测试的失败原因搅浑。
     */
    private fun packageOf(text: String): String = when {
        text.contains("微信") -> "com.tencent.mm"
        text.contains("支付宝") -> "com.eg.android.AlipayGphone"
        else -> "com.example.unknown"
    }
}
