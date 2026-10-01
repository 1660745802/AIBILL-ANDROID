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
     * **最近一次成功登录过的用户 ID**，与当前 session 解耦。
     *
     * 用途：`AuthRepositoryImpl` 登录时判定「同账号重登」还是「换号」，
     * 从而决定**是否清空本地离线队列**（见 ARCHITECTURE §6.4）。
     *
     * ⚠️ 为什么不能用 [getUserId]：401 时 [AuthInterceptor] 会调 [clearSession] 把整个
     * session（含 user_id）清掉。而「Token 过期 → 重登」恰恰是最需要保住离线队列的场景——
     * 若此时 user_id 已被抹掉，登录侧就只能保守地判定为「身份未知」，
     * 从而**删掉用户未同步的记账**。Token 过期并不改变「这台设备上的用户是谁」，
     * 所以身份信息必须活得比 session 长。
     *
     * 与 [clearSession] 的区别：本字段**不**随 401 清除，只在显式登出时清除。
     */
    fun getLastKnownUserId(): Int? =
        if (prefs.contains(KEY_LAST_USER_ID)) prefs.getInt(KEY_LAST_USER_ID, -1).takeIf { it >= 0 } else null

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
            .putInt(KEY_LAST_USER_ID, userId)
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
     * PR M1：清空当前 session（token + userId + username + nickname）。
     * 401（Token 过期）时也会调这个——此时**故意保留** [KEY_LAST_USER_ID]，
     * 好让用户重登时能识别出「同账号」而不误删离线队列（见 [getLastKnownUserId]）。
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .commit()
    }

    /**
     * 显式登出：连同 [KEY_LAST_USER_ID] 一起清干净。
     * 下次登录时既没有 session 也没有历史身份 → 必然走「首次登录」路径（清本地缓存）。
     */
    fun clearSessionAndIdentity() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .remove(KEY_LAST_USER_ID)
            .commit()
    }

    fun hasToken(): Boolean = getToken() != null

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"

        /** 跨 session 保留的「最近登录用户 ID」，不随 401 清除 */
        private const val KEY_LAST_USER_ID = "last_known_user_id"
    }
}
