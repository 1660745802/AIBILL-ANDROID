package com.aibill.android.data.notification.rules

import com.aibill.android.domain.notification.rules.NotificationRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知规则的对外门面（C04 决策 3 后）。
 *
 * 原先一人兼六职（SP 落盘 / ETag+HTTP / 代际缓存 / assets 默认 / 内联兜底 / 映射转发），
 * 现已拆成三个单一关注点：
 * - [RulesDefaults]：assets 默认 + 内联兜底；
 * - [RulesStore]：SP 落盘 + 代际缓存；
 * - [RulesFetcher]：HTTP + ETag/304 + 三态结果；
 * 映射本体是独立的 [NotificationRulesMapping]。
 *
 * 本类只保留消费者需要的两个入口（`getSnapshot` / `fetchRules`）与其结果类型，
 * 内部一律委托。消费者（`CloudNotificationRuleSource`、`SettingsViewModel`、
 * `RulesSyncWorker`、`AiBillApp`）无需感知拆分。
 */
@Singleton
class NotificationRulesManager @Inject constructor(
    private val store: RulesStore,
    private val fetcher: RulesFetcher,
) {
    data class RulesSnapshot(
        val rules: NotificationRules,
        val generation: Int,
    )

    /**
     * [fetchRules] 的结果：区分「真更新 / 已是最新 / 拉取失败」三态，
     * 避免断网点同步也报成功（假反馈）。
     */
    sealed interface RulesFetchResult {
        /** 服务端下发了新规则，已写入本地并生效。 */
        data class Updated(val version: Int) : RulesFetchResult

        /** ETag 命中 304，本地规则本来就是最新的。 */
        data object NotModified : RulesFetchResult

        /** 拉取失败，**保留现有规则继续工作**（不影响已记账功能）。 */
        data class Failed(val message: String) : RulesFetchResult
    }

    /** 原子读取当前规则及其代际。 */
    fun getSnapshot(): RulesSnapshot = store.snapshot()

    /** 从服务器拉取规则（三态）。失败不抛异常、保留现有规则。 */
    suspend fun fetchRules(): RulesFetchResult = fetcher.fetch()
}
