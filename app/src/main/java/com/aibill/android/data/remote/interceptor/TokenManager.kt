package com.aibill.android.data.remote.interceptor

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话结束原因（持久化）。
 *
 * 存在的理由：冷启动时需要区分「用户主动退出登录」与「被后台 401 静默踢掉」，
 * 二者对用户是完全不同的体验。但**登录态本身区分不了**——两者都是「没有 token」。
 * 早期实现靠 `lastKnownUserId != null && !hasToken()` 猜测；一旦显式登出也保留
 * `last_known_user_id`（B6 修复的前提），这个猜测就会把主动登出误报成「登录已过期」。
 * 因此需要一个显式、持久化的判据，而不是从状态反推。
 */
enum class SessionEndReason(val storedValue: String) {
    /**
     * 请求携带了 token 却被服务端 401 拒绝 → 会话真的过期了。
     * 用户事前不知情（典型：通知监听在后台调 AI 时过期），冷启动应给出说明。
     */
    TOKEN_EXPIRED("token_expired"),

    /**
     * 本来就没有会话可结束：用户主动退出登录，或本次请求未携带凭证就被拒
     * （登录密码错误、首次安装未登录等）。属于**预期内**，不应打扰用户。
     */
    SIGNED_OUT("signed_out"),
}

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
 *
 * ## 构造 seam
 *
 * 本类只依赖 [SharedPreferences] 这个接口，**不**依赖 `EncryptedSharedPreferences`：
 * 所有读写语义（原子 commit、remove、contains）与具体存储无关。
 * 因此生产入口用 [EncryptedSharedPreferences]，测试入口用普通实现，
 * 两者跑的是**同一份逻辑**——这正是 B6 不变量能被真实锁住的前提。
 *
 * 加密创建逻辑（[createEncryptedPrefs]）保持 private，不对外暴露：
 * 生产行为无法被测试绕过成「明文存储」。
 */
@Singleton
class TokenManager internal constructor(
    private val prefs: SharedPreferences,
) {
    /** 生产入口：加密存储。Hilt 注入点。 */
    @Inject
    constructor(@ApplicationContext context: Context) : this(createEncryptedPrefs(context))

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    /**
     * **最近一次成功登录过的用户 ID**，与当前 session 解耦。
     *
     * 用途：本地数据归属 module（`LocalDataOwnership.currentOwnership()`，架构候选 4）
     * 据此判定「同账号重登」还是「换号」，从而决定**是否清空本地缓存**
     * （见 ARCHITECTURE §6.4）。
     *
     * ⚠️ 为什么不能用 session 里的 `user_id`：401 / 登出时 [endSession] 会把整个
     * session（含 user_id）清掉。而「Token 过期 → 重登」恰恰是最需要保住本地缓存的场景——
     * 若此时 user_id 已被抹掉，登录侧就只能保守地判定为「身份未知」，
     * 从而**删掉用户的本地缓存与通知审计**。Token 过期并不改变「这台设备上的用户是谁」，
     * 所以身份信息必须活得比 session 长。
     *
     * 与 [endSession] 的区别：本字段**任何**会话结束路径都不清除（401 不清、显式登出也不清），
     * 它不是 session 的一部分，而是**本地缓存的归属判据**。
     */
    fun getLastKnownUserId(): Int? =
        if (prefs.contains(KEY_LAST_USER_ID)) prefs.getInt(KEY_LAST_USER_ID, -1).takeIf { it >= 0 } else null

    /**
     * PR M1：原子写入 token + session 信息。
     * 使用 commit() 而非 apply() 强制同步落盘，避免进程被杀导致
     * token 已写、userInfo 还在 DataStore 的不一致窗口。
     *
     * 同时清除 [KEY_SESSION_END_REASON]：登录成功意味着上一次会话结束已被处理完，
     * 若不清理，下次冷启动会拿旧标记误报「登录已过期」。
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
            .remove(KEY_SESSION_END_REASON)
            .commit()
    }

    /**
     * 会话结束时的**唯一**入口：清空 token + 当前用户字段，
     * 并持久化结束原因供冷启动判读。
     *
     * 两种调用方，清理动作相同、语义不同：
     * - [SessionEndReason.TOKEN_EXPIRED]：`AuthInterceptor` 收到**带凭证**的 401
     * - [SessionEndReason.SIGNED_OUT]：用户主动退出登录（`AuthRepositoryImpl.logout`），
     *   或本次请求本就未携带凭证却被拒（登录密码错误、首次安装等）
     *
     * ⚠️ **一律保留 [KEY_LAST_USER_ID]**，显式登出也不能删。
     * 删了它，用户登出再登回**同一账号**时本地数据归属 module 会因
     * `accountId == null` 判定为「首次登录」而走
     * `DisposalIntent.SwitchingAccount`，
     * 把用户的本地数据物理删除——而 `ProfileScreen` 的登出弹窗向用户承诺过
     * 「本地数据不会丢失」。跨账号防护靠的是
     * `accountId != incomingUserId` 的比较，不靠 `accountId != null`。
     */
    fun endSession(reason: SessionEndReason) {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .putString(KEY_SESSION_END_REASON, reason.storedValue)
            .commit()
    }

    /**
     * 上一次会话是怎么结束的；`null` = 无已知的会话结束（首次安装 / 登录成功后被清除）。
     * 供冷启动区分「被后台 401 踢掉」与「用户自己退出的」。
     */
    fun getSessionEndReason(): SessionEndReason? {
        val stored = prefs.getString(KEY_SESSION_END_REASON, null) ?: return null
        return SessionEndReason.entries.firstOrNull { it.storedValue == stored }
    }

    fun hasToken(): Boolean = getToken() != null

    companion object {
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NICKNAME = "nickname"

        /** 跨 session 保留的「最近登录用户 ID」，不随任何会话结束清除 */
        private const val KEY_LAST_USER_ID = "last_known_user_id"

        /** 上次会话结束的原因；[saveSession] 成功后清除 */
        private const val KEY_SESSION_END_REASON = "session_end_reason"

        private const val ENCRYPTED_PREFS_NAME = "secure_prefs"

        /**
         * 生产用加密存储。**保持 private**：它是加密边界的实现细节，
         * 暴露成 public 等于允许调用方绕过加密。
         */
        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
