package com.aibill.android.data.remote.interceptor

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token + Session 安全存储
 *
 * PR #58：登录成功后立即持久化，避免进程被杀丢失
 * PR M1：把 userId/username/nickname 也写入 EncryptedSharedPreferences，
 * 与 token 在同一 commit() 里原子提交，避免
 * "token 已写入但 userInfo 还在 DataStore" 的不一致窗口。
 *
 * DataStore 里仍保留旧字段作为只读 fallback（不删除，避免迁移期
 * 老用户首次启动读到 null），新写入全部走这里。
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    /**
     * PR M1：原子写入 token + session 信息。
     * 使用 commit() 而非 apply() 强制同步落盘，避免进程被杀导致
     * token 已写、userInfo 还在 DataStore 的不一致窗口。
     */
    fun saveSession(
        token: String,
        userId: Int,
        username: String,
        nickname: String?,
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_NICKNAME, nickname.orEmpty())
            .commit()
    }

    fun getUserId(): Int? = if (prefs.contains(KEY_USER_ID)) prefs.getInt(KEY_USER_ID, -1).takeIf { it >= 0 } else null
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotEmpty() }
    fun getNickname(): String? = prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotEmpty() }

    /** 兼容旧调用：单独写 token（仅在没有 session 信息时用） */
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /**
     * PR M1：清空整个 session（token + userId + username + nickname）。
     * 登出时一次性 commit 删除所有字段，避免漏删。
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .commit()
    }

    fun hasToken(): Boolean = getToken() != null

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"
    }
}