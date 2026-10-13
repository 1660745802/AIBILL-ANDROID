package com.aibill.android.data.notification

import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.data.remote.dto.response.PrecheckDto
import com.aibill.android.domain.notification.PrecheckRules
import com.aibill.android.data.notification.rules.NotificationRulesMapping
import com.aibill.android.util.AppLogger
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okio.Buffer

/**
 * **服务端真实路由导出**的 precheck 载荷（`GET /api/config/notification-rules → data.rules.precheck`）
 * 的测试夹具加载器。
 *
 * ## 为什么必须是真载荷，而不是「近似夹具」
 *
 * `PrefilterTestHarness.precheckSpec()` 是手写的近似夹具：它把 `verb` / `bare_amount` 的
 * flags 写成 `"i"`，恰好把服务端真实形状 `flags == ""`（合法 = 无 flag）掩盖掉；
 * 于是一整条「空串被判缺失 → 半个判据链失效 → 真交易被 no_signal 拦」的 P0 缺陷
 * 在 496 个用例里都没被发现（见 commit 8694c09）。
 *
 * 本对象把真载荷（含 `source` / `content_hash` / `version` / `corpus_hash` / `precheck`
 * 五个顶层键，meta 字段**原样保留**）按**生产路径**喂进判据：
 *
 * `precheck` 节点 → [PrecheckDto]（Moshi codegen，不手工构造）
 * → [NotificationRulesDto]（`precheck = …`）
 * → [NotificationRulesMapping.fromDto]（其余字段走 [PrefilterTestHarness.rules] 惰性兜底）
 * → [deriveSnapshot]（生产编译入口）→ [PrecheckRules]。
 *
 * 路径只认「相对路径 + `app/` 前缀回退」（本项目其它测试的约定），
 * 不写死绝对路径——CI 上没有 Downloads 目录。
 */
internal object PrecheckRealPayload {

    private const val FILE_NAME = "precheck-payload.json"

    /** 夹具文本（meta 字段原样保留）。 */
    fun text(): String = TestResources.file(FILE_NAME).readText()

    /** 真载荷的内容 hash（`precheck` 判据本身的指纹）。 */
    fun contentHash(): String = meta("content_hash")

    /** 真载荷的语料 hash（与服务端 dump 的 `corpus_hash` 应逐字相同）。 */
    fun corpusHash(): String = meta("corpus_hash")

    /**
     * 生产路径编译：真载荷的 `precheck` 节点 → 已编译 [PrecheckRules]。
     *
     * `precheck` 子树用 [JsonReader] 定位后直接交给 [PrecheckDto] 的 Moshi adapter
     * （不经 `Map<String, Any>` 往返，避免整数被读成 Double 后 `tier` 解析失败）。
     */
    fun compileRules(logger: AppLogger?): PrecheckRules {
        val moshi = Moshi.Builder().build()
        val dto = readPrecheckNode(moshi)
        val rules = NotificationRulesMapping.fromDto(
            NotificationRulesDto(
                nls = null,
                a11y = null,
                smsRules = null,
                sourceMapping = null,
                processor = null,
                precheck = dto,
            ),
        ) { PrefilterTestHarness.rules() }
        // 与生产同一条编译入口（generation 用 1，量纲只影响缓存，不影响判据）
        return deriveSnapshot(rules, generation = 1, logger = logger).precheck
    }

    private fun readPrecheckNode(moshi: Moshi): PrecheckDto {
        val adapter = moshi.adapter(PrecheckDto::class.java)
        val reader = JsonReader.of(Buffer().writeUtf8(text()))
        var dto: PrecheckDto? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "precheck") {
                dto = adapter.fromJson(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return dto ?: error("真实载荷缺 precheck 节点")
    }

    private fun meta(key: String): String {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val root = Moshi.Builder().build().adapter<Map<String, Any?>>(type).fromJson(text())
            ?: error("真实载荷解析失败")
        return root[key] as? String ?: error("真实载荷缺 meta 字段 $key")
    }
}
