package com.aibill.android.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/**
 * 用户偏好设置存储（非敏感数据）
 * Token 存储在 EncryptedSharedPreferences 中，不在此处
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // --- Keys ---
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USER_ID = intPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val NICKNAME = stringPreferencesKey("nickname")
        val DEFAULT_ACCOUNT_ID = intPreferencesKey("default_account_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
        val NOTIFICATION_PRIVACY = booleanPreferencesKey("notification_privacy")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val QUICK_ENTRY_ENABLED = booleanPreferencesKey("quick_entry_enabled")
        val AUTOMATION_LEVEL = stringPreferencesKey("automation_level")
        val SMALL_AMOUNT_THRESHOLD = intPreferencesKey("small_amount_threshold")
    }

    // --- Server URL ---
    val serverUrl: Flow<String?> = dataStore.data.map { it[Keys.SERVER_URL] }

    suspend fun setServerUrl(url: String) {
        dataStore.edit { it[Keys.SERVER_URL] = url }
    }

    // --- User Info ---
    val userId: Flow<Int?> = dataStore.data.map { it[Keys.USER_ID] }
    val username: Flow<String?> = dataStore.data.map { it[Keys.USERNAME] }
    val nickname: Flow<String?> = dataStore.data.map { it[Keys.NICKNAME] }

    suspend fun setUserInfo(id: Int, username: String, nickname: String?) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = id
            prefs[Keys.USERNAME] = username
            nickname?.let { prefs[Keys.NICKNAME] = it }
        }
    }

    // --- Default Account ---
    val defaultAccountId: Flow<Int?> = dataStore.data.map { it[Keys.DEFAULT_ACCOUNT_ID] }

    suspend fun setDefaultAccountId(id: Int) {
        dataStore.edit { it[Keys.DEFAULT_ACCOUNT_ID] = id }
    }

    // --- Theme ---
    val themeMode: Flow<String> = dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    // --- Notification ---
    val notificationEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.NOTIFICATION_ENABLED] ?: false }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_ENABLED] = enabled }
    }

    // --- Sync ---
    val lastSyncTime: Flow<Long?> = dataStore.data.map { it[Keys.LAST_SYNC_TIME] }

    suspend fun setLastSyncTime(time: Long) {
        dataStore.edit { it[Keys.LAST_SYNC_TIME] = time }
    }

    // --- Hide from Recents ---
    val hideFromRecents: Flow<Boolean> = dataStore.data.map { it[Keys.HIDE_FROM_RECENTS] ?: false }

    suspend fun setHideFromRecents(enabled: Boolean) {
        dataStore.edit { it[Keys.HIDE_FROM_RECENTS] = enabled }
    }

    // --- Notification Privacy ---
    val notificationPrivacy: Flow<Boolean> = dataStore.data.map { it[Keys.NOTIFICATION_PRIVACY] ?: false }

    suspend fun setNotificationPrivacy(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_PRIVACY] = enabled }
    }

    // --- App Lock ---
    val appLockEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    // --- Quick Entry ---
    val quickEntryEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.QUICK_ENTRY_ENABLED] ?: false }

    suspend fun setQuickEntryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.QUICK_ENTRY_ENABLED] = enabled }
    }

    // --- Automation Level ---
    val automationLevel: Flow<String> = dataStore.data.map { it[Keys.AUTOMATION_LEVEL] ?: "standard" }

    suspend fun setAutomationLevel(level: String) {
        dataStore.edit { it[Keys.AUTOMATION_LEVEL] = level }
    }

    // --- Small Amount Threshold ---
    val smallAmountThreshold: Flow<Int> = dataStore.data.map { it[Keys.SMALL_AMOUNT_THRESHOLD] ?: 1000 }

    suspend fun setSmallAmountThreshold(cents: Int) {
        dataStore.edit { it[Keys.SMALL_AMOUNT_THRESHOLD] = cents }
    }

    // --- Clear All (logout) ---
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
