package com.aibill.android.data.notification

import com.aibill.android.data.notification.rules.NotificationRulesMapping
import com.aibill.android.data.remote.dto.response.NotificationRulesData
import com.aibill.android.domain.notification.rules.NotificationRules
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * **服务端真实发布载荷**（`GET /api/config/notification-rules → data`）的测试夹具加载器。
 *
 * ## 为什么必须是真载荷，而不是一份手工维护的 `scripts/rules.json`
 *
 * 旧的 `NotificationFilterRegressionTest` 直接读 `scripts/rules.json`，声称「单一数据源」。
 * 那份副本与真源 `server/config/rules.json` 早已结构性漂移：缺 `per_package` / `default_rule`、
 * 词表不同、缺全部 `a11y.*`。于是护栏一直在测一条**生产到不了**的分支
 * （生产因缺键回退 `assets` 空表 → 走 legacy 硬编码），它挡下的广告在线上可能根本没被挡。
 *
 * 这与 `PrecheckRealPayload` 是同一个教训：手写/手拷的近似夹具会掩盖真实缺陷。
 *
 * ## 夹具怎么来
 *
 * `server/scripts/export-client-fixture.ts` 从真源导出**发布载荷**（剔除 testcases +
 * 代码派生 precheck），带 provenance：
 * `{ source, version, content_hash, corpus_hash, rules }`（meta 字段原样保留）。
 * 服务端 `tests/lib/notification-rules-fixture.test.ts` 会在真源改了却未重导时变红。
 *
 * 路径只认「相对路径 + `app/` 前缀回退」（见 [TestResources]），不写死绝对路径。
 */
internal object NotificationRulesRealPayload {

    private const val FILE_NAME = "notification-rules-payload.json"

    /** 夹具文本（meta 字段原样保留）。 */
    fun text(): String = TestResources.file(FILE_NAME).readText()

    /** 发布载荷的内容 hash（判据本身的指纹；真源改了没重导 → 与真源对不上）。 */
    fun contentHash(): String = meta("content_hash")

    /** 语料（testcases）hash——**随载荷一起导出**，让「客户端 vendor 的副本陈旧」可见。 */
    fun corpusHash(): String = meta("corpus_hash")

    /** 整份载荷 → [NotificationRulesData]（多出的 meta 键被 Moshi 忽略）。 */
    fun data(): NotificationRulesData =
        Moshi.Builder().build()
            .adapter(NotificationRulesData::class.java)
            .fromJson(text())
            ?: error("真实载荷解析失败：$FILE_NAME")

    /** 走**生产映射**得到规则模型（云控字段齐全，不应回退 assets 兜底）。 */
    fun rules(): NotificationRules =
        NotificationRulesMapping.fromDto(data().rules ?: error("真实载荷缺 rules 段")) {
            error("真实载荷字段齐全，不应回退 assets 兜底")
        }

    private fun meta(key: String): String {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val root = Moshi.Builder().build().adapter<Map<String, Any?>>(type).fromJson(text())
            ?: error("真实载荷解析失败")
        return root[key] as? String ?: error("真实载荷缺 meta 字段 $key")
    }
}
