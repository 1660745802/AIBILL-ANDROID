package com.aibill.android.data.notification.rules

import com.aibill.android.data.remote.api.NotificationRulesApi
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.squareup.moshi.Moshi
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则拉取（C04 决策 3：从 [NotificationRulesManager] 拆出的第二块）。
 *
 * 独占：HTTP 拉取 + ETag/304 + 三态结果 [NotificationRulesManager.RulesFetchResult]。
 * **不抛异常**：失败返回 `Failed` 并保留现有规则（云控挂了不影响已记账功能）。
 * 拉到 200 时把 raw JSON / ETag / 解析后的规则交给 [RulesStore] 落盘。
 */
@Singleton
class RulesFetcher @Inject constructor(
    private val api: NotificationRulesApi,
    private val store: RulesStore,
    private val moshi: Moshi,
) {
    suspend fun fetch(): NotificationRulesManager.RulesFetchResult {
        return try {
            val etag = store.etag()
            Timber.d("NotificationRules: fetching from server (etag=${etag?.take(16) ?: "none"})")
            val response = api.getRules(etag)

            when (response.code()) {
                304 -> {
                    Timber.d("NotificationRules: 304 Not Modified, rules unchanged")
                    NotificationRulesManager.RulesFetchResult.NotModified
                }
                200 -> {
                    val body = response.body()
                    val rulesDto = body?.data?.rules
                    if (body?.code == 0 && rulesDto != null) {
                        val json = moshi.adapter(NotificationRulesDto::class.java).toJson(rulesDto)
                        val newEtag = response.headers()["ETag"]
                        // 缺字段时的兜底 = 当前已生效规则（比 assets 更贴近线上），再兜 defaults（由 store 负责）
                        val rules = NotificationRulesMapping.fromDto(rulesDto) { store.snapshot().rules }
                        store.save(json, newEtag, rules)
                        Timber.d(
                            "NotificationRules: updated to version=${body.data.version} etag=${newEtag?.take(16)} " +
                                "sourceMapping=${rulesDto.sourceMapping?.size ?: 0} " +
                                "nlsSmsPackages=${rulesDto.nls?.smsPackages?.size ?: 0}"
                        )
                        NotificationRulesManager.RulesFetchResult.Updated(body.data.version)
                    } else {
                        Timber.w("NotificationRules: 200 but code=${body?.code} or rules=null")
                        NotificationRulesManager.RulesFetchResult.Failed("服务端返回异常，请稍后重试")
                    }
                }
                else -> {
                    Timber.w("NotificationRules: unexpected HTTP ${response.code()}")
                    NotificationRulesManager.RulesFetchResult.Failed("服务器错误 ${response.code()}")
                }
            }
        } catch (e: IOException) {
            Timber.w(e, "NotificationRules: fetch failed, keeping current rules")
            NotificationRulesManager.RulesFetchResult.Failed("网络连接失败，请检查服务器地址")
        } catch (e: Exception) {
            Timber.w(e, "NotificationRules: fetch failed, keeping current rules")
            NotificationRulesManager.RulesFetchResult.Failed(e.message ?: "未知错误")
        }
    }
}
