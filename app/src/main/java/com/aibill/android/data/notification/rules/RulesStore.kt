package com.aibill.android.data.notification.rules

import android.app.Application
import com.aibill.android.data.remote.dto.response.NotificationRulesDto
import com.aibill.android.domain.notification.rules.NotificationRules
import com.squareup.moshi.Moshi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则存储（C04 决策 3：从 [NotificationRulesManager] 拆出的第一块）。
 *
 * 独占：SharedPreferences 落盘/读回（JSON + ETag）与**代际缓存**（`cachedSnapshot`）。
 * 优先级：内存缓存 → SharedPreferences → [RulesDefaults]。这是纯本地关注点，不碰网络。
 */
@Singleton
class RulesStore @Inject constructor(
    private val application: Application,
    private val moshi: Moshi,
    private val defaults: RulesDefaults,
) {
    private val prefs by lazy {
        application.getSharedPreferences("notification_rules", android.content.Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedSnapshot: NotificationRulesManager.RulesSnapshot? = null

    /** 原子读取规则及其代际；首次调用从 SP 恢复，失败回落默认值。 */
    fun snapshot(): NotificationRulesManager.RulesSnapshot {
        cachedSnapshot?.let { return it }
        return synchronized(this) {
            cachedSnapshot ?: loadInitial().also { cachedSnapshot = it }
        }
    }

    /** 上次拉取用的 ETag（`If-None-Match`）。 */
    fun etag(): String? = prefs.getString(KEY_ETAG, null)

    /** 云控 200：落盘 raw JSON + ETag，并更新内存缓存（代际自增）。 */
    fun save(json: String, etag: String?, rules: NotificationRules) {
        prefs.edit()
            .putString(KEY_JSON, json)
            .putString(KEY_ETAG, etag)
            .apply()
        setCached(rules)
    }

    private fun loadInitial(): NotificationRulesManager.RulesSnapshot {
        val json = prefs.getString(KEY_JSON, null)
        if (json != null) {
            try {
                val dto = moshi.adapter(NotificationRulesDto::class.java).fromJson(json)
                if (dto != null) {
                    val rules = NotificationRulesMapping.fromDto(dto) { defaults.rules }
                    Timber.d("NotificationRules: restored from SP (sourceMapping=${rules.sourceMapping.size})")
                    return NotificationRulesManager.RulesSnapshot(rules, 1)
                }
            } catch (e: Exception) {
                Timber.w(e, "NotificationRules: parse cached JSON failed, falling back to defaults")
            }
        }

        Timber.d("NotificationRules: no cached rules, using defaults from assets")
        return NotificationRulesManager.RulesSnapshot(defaults.rules, 0)
    }

    @Synchronized
    private fun setCached(rules: NotificationRules) {
        val generation = (cachedSnapshot?.generation ?: 0) + 1
        cachedSnapshot = NotificationRulesManager.RulesSnapshot(rules, generation)
        Timber.d("NotificationRules: cache updated, generation=$generation")
    }

    private companion object {
        const val KEY_JSON = "notification_rules_json"
        const val KEY_ETAG = "notification_rules_etag"
    }
}
