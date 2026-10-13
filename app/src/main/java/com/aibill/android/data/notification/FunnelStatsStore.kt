package com.aibill.android.data.notification

import com.aibill.android.data.local.datastore.UserPreferences
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 漏斗计数的**持久化**存储（P0-2）。
 *
 * `CaptureFunnelStats` 原先纯内存，进程被杀即清零 → 「AI 失败率」系统性偏低，而失败率是
 * 「AI 失败不打扰用户」这条产品取舍的**唯一补偿**。这里把「还没上报的计数」落到单行 JSON，
 * 重建后恢复；上报（drain）+ [CaptureFunnelStats.reset] 时才清。
 *
 * 键名 / 窗口 / 上报语义都不变：持久化只解决「重启丢计数」，不改变计数口径。
 */
interface FunnelStatsStore {

    /** 读取上次未上报的计数（进程启动时读一次）。 */
    suspend fun load(): Map<String, Long>

    /** 覆盖落盘当前计数快照。 */
    suspend fun save(counts: Map<String, Long>)

    /** 清空（上报后调用）。 */
    suspend fun clear()
}

/** 单测 / 无持久化场景：全部 no-op。 */
object NoopFunnelStatsStore : FunnelStatsStore {
    override suspend fun load(): Map<String, Long> = emptyMap()
    override suspend fun save(counts: Map<String, Long>) = Unit
    override suspend fun clear() = Unit
}

/** 落盘形态：单行 JSON（`Map<String, Long>` 走 Moshi 内置适配器）。 */
@JsonClass(generateAdapter = true)
internal data class PersistedFunnelCounts(
    @Json(name = "counts") val counts: Map<String, Long> = emptyMap(),
)

/** 生产实现：落 `UserPreferences`（Preferences DataStore）的一个字符串键。 */
@Singleton
class DataStoreFunnelStatsStore @Inject constructor(
    private val userPreferences: UserPreferences,
    moshi: Moshi,
) : FunnelStatsStore {

    private val adapter = moshi.adapter(PersistedFunnelCounts::class.java)

    override suspend fun load(): Map<String, Long> {
        val raw = userPreferences.captureFunnelCounts.first() ?: return emptyMap()
        return runCatching { adapter.fromJson(raw)?.counts }.getOrNull().orEmpty()
    }

    override suspend fun save(counts: Map<String, Long>) {
        userPreferences.setCaptureFunnelCounts(adapter.toJson(PersistedFunnelCounts(counts)))
    }

    override suspend fun clear() {
        userPreferences.clearCaptureFunnelCounts()
    }
}
