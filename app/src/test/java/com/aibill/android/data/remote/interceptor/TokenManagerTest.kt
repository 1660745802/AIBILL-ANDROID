package com.aibill.android.data.remote.interceptor

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `TokenManager` 的**真实**持久化行为测试（Robolectric + 普通 SharedPreferences，非 mock）。
 *
 * ## 为什么以前测不了
 *
 * 生产构造需要 `EncryptedSharedPreferences` → `MasterKey` → **AndroidKeyStore**，
 * 而 Robolectric 不提供 AndroidKeyStore（`KeyStoreException: AndroidKeyStore not found`）。
 * 于是 `TokenManager` 历史上**没有任何真实测试**，`AuthRepositoryImplTest` 只能把它 mock 掉。
 *
 * 由此产生一个实测到的严重缺口（G1）：把 `endSession` 改回删除 `last_known_user_id`
 * （即 B6 原 bug），**整个测试套件静默全绿**——因为 mock 出来的
 * `getLastKnownUserId()` 永远是测试自己写的值，真实实现删没删这个 key 根本没人验证。
 *
 * ## 本测试如何关闭它
 *
 * `TokenManager` 的主构造只依赖 `SharedPreferences` 接口，本类用**普通**实现构造它。
 * 生产入口（`@Inject constructor(Context)`）仍然创建 `EncryptedSharedPreferences`，
 * 但**读写逻辑是同一份**——所以这里验证的正是线上跑的那段代码。
 *
 * `application = Application::class` 必需：默认会按 manifest 启动真实 `AiBillApp`，
 * 其 Hilt 图会构造 TokenManager → EncryptedSharedPreferences → AndroidKeyStore，
 * 在 Robolectric 沙箱里抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TokenManagerTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var tokenManager: TokenManager

    /** 生产用的 key 是 private，这里用字面量访问；用 round-trip 断言证明 key 本身没写错。 */
    private val keySessionEndReason = "session_end_reason"
    private val keyLastKnownUserId = "last_known_user_id"
    private val keyUserId = "user_id"
    private val keyUsername = "username"
    private val keyNickname = "nickname"

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("token_manager_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        tokenManager = TokenManager(prefs)
    }

    // ==================== saveSession ====================

    @Test
    fun `saveSession writes token current user and lastKnown, and clears stale session-end reason`() {
        // 先制造一个「上次会话已被结束」的现场
        tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)
        assertEquals(SessionEndReason.TOKEN_EXPIRED, tokenManager.getSessionEndReason())

        tokenManager.saveSession(
            token = "jwt-xyz",
            userId = 42,
            username = "alice",
            nickname = "Alice",
        )

        assertEquals("jwt-xyz", tokenManager.getToken())
        assertEquals(42, prefs.getInt(keyUserId, -1))
        assertEquals("alice", prefs.getString(keyUsername, null))
        assertEquals("Alice", prefs.getString(keyNickname, null))
        assertEquals(42, tokenManager.getLastKnownUserId())
        assertTrue(tokenManager.hasToken())
        // 登录成功 = 上一次会话结束已被处理完，标记必须清掉，
        // 否则下次冷启动会拿旧标记误报「登录已过期」
        assertNull(tokenManager.getSessionEndReason())
        assertFalse(prefs.contains(keySessionEndReason))
    }

    @Test
    fun `saveSession with null nickname stores empty string`() = runBlocking {
        tokenManager.saveSession("jwt-1", 7, "bob", nickname = null)

        assertEquals("", prefs.getString(keyNickname, null))
    }

    // ==================== endSession：B6 的核心不变量 ====================

    @Test
    fun `endSession SIGNED_OUT clears token and current user but KEEPS lastKnown`() {
        tokenManager.saveSession("jwt-xyz", 42, "alice", "Alice")

        tokenManager.endSession(SessionEndReason.SIGNED_OUT)

        // session 被清
        assertNull(tokenManager.getToken())
        assertFalse(prefs.contains(keyUserId))
        assertFalse(prefs.contains(keyUsername))
        assertFalse(prefs.contains(keyNickname))
        assertFalse(tokenManager.hasToken())
        // 结束原因被持久化，供冷启动区分「主动退出」
        assertEquals(SessionEndReason.SIGNED_OUT, tokenManager.getSessionEndReason())
        assertEquals("signed_out", prefs.getString(keySessionEndReason, null))
        // ⚠️ B6 的命脉：lastKnown 必须活着，否则「登出→登回同账号」会退化成
        // 「首次登录」并 deleteAll()，把用户离线记的账物理删除
        assertEquals(42, tokenManager.getLastKnownUserId())
        assertEquals(42, prefs.getInt(keyLastKnownUserId, -1))
    }

    @Test
    fun `endSession TOKEN_EXPIRED also KEEPS lastKnown (401 path must not regress it)`() {
        tokenManager.saveSession("jwt-xyz", 42, "alice", "Alice")

        tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)

        assertNull(tokenManager.getToken())
        assertFalse(prefs.contains(keyUserId))
        assertEquals(SessionEndReason.TOKEN_EXPIRED, tokenManager.getSessionEndReason())
        assertEquals("token_expired", prefs.getString(keySessionEndReason, null))
        // 401 路径历史上一直保留 lastKnown（B6 之前的行为），不得回归
        assertEquals(42, tokenManager.getLastKnownUserId())
    }

    @Test
    fun `endSession is idempotent and does not touch lastKnown across repeated calls`() {
        tokenManager.saveSession("jwt-xyz", 42, "alice", "Alice")

        tokenManager.endSession(SessionEndReason.SIGNED_OUT)
        tokenManager.endSession(SessionEndReason.SIGNED_OUT)
        tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)

        assertEquals(42, tokenManager.getLastKnownUserId())
        assertEquals(SessionEndReason.TOKEN_EXPIRED, tokenManager.getSessionEndReason())
    }

    // ==================== lastKnown 跨 session 传递 ====================

    @Test
    fun `next login updates lastKnown and clears previous reason`() {
        tokenManager.saveSession("jwt-1", 7, "bob", "Bob")
        tokenManager.endSession(SessionEndReason.TOKEN_EXPIRED)
        assertEquals(SessionEndReason.TOKEN_EXPIRED, tokenManager.getSessionEndReason())

        // 换号登录：lastKnown 必须被更新成新账号
        tokenManager.saveSession("jwt-2", 99, "carol", "Carol")

        assertEquals(99, tokenManager.getLastKnownUserId())
        assertEquals("carol", prefs.getString(keyUsername, null))
        assertNull(tokenManager.getSessionEndReason())
    }

    // ==================== 未知 storedValue 的容错 ====================

    @Test
    fun `unknown storedValue reads back as null instead of throwing`() {
        // 模拟 storedValue 被改名 / 数据被外部篡改的脏状态
        prefs.edit().putString(keySessionEndReason, "some_removed_reason_v9").commit()

        assertNull(tokenManager.getSessionEndReason())

        // round-trip 自证：同一个 key 写入已知值必须能读回，
        // 否则上面的 null 可能只是因为 key 名写错而非「容错生效」
        prefs.edit().putString(keySessionEndReason, SessionEndReason.SIGNED_OUT.storedValue).commit()
        assertEquals(SessionEndReason.SIGNED_OUT, tokenManager.getSessionEndReason())
    }

    @Test
    fun `fresh instance has no session end reason and no lastKnown`() = runBlocking {
        // 全新安装的语义：什么都没有，冷启动不该提示任何东西
        val empty = TokenManager(
            RuntimeEnvironment.getApplication()
                .getSharedPreferences("token_manager_empty_prefs", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() }
        )

        assertNull(empty.getSessionEndReason())
        assertNull(empty.getLastKnownUserId())
        assertNull(empty.getToken())
        assertFalse(empty.hasToken())
    }
}
