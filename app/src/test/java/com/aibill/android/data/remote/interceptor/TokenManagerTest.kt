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
    fun `endSession clears the session but keeps lastKnown and device keys, for every reason`() {
        // 两种结束路径的清理动作相同，差异只在持久化的 reason；一一验证不得回归
        SessionEndReason.entries.forEach { reason ->
            prefs.edit().clear().commit()
            tokenManager.saveSession("jwt-xyz", 42, "alice", "Alice")
            // 设备/其它 key 不属于 session：endSession 用显式 remove 而非 clear()
            prefs.edit().putString("unrelated_device_key", "keep-me").commit()

            tokenManager.endSession(reason)

            // session 被清
            assertNull(tokenManager.getToken())
            assertFalse(prefs.contains(keyUserId))
            assertFalse(prefs.contains(keyUsername))
            assertFalse(prefs.contains(keyNickname))
            assertFalse(tokenManager.hasToken())
            // 结束原因被持久化，供冷启动区分
            assertEquals(reason, tokenManager.getSessionEndReason())
            assertEquals(reason.storedValue, prefs.getString(keySessionEndReason, null))
            // ⚠️ B6 的命脉：lastKnown 必须活着，否则「登出→登回同账号」会退化成
            // 「首次登录」并 deleteAll()，把用户离线记的账物理删除
            assertEquals(42, tokenManager.getLastKnownUserId())
            assertEquals(42, prefs.getInt(keyLastKnownUserId, -1))
            // 只删 session 三项 + 写 reason，不能顺手 clear() 整个 prefs
            assertEquals("keep-me", prefs.getString("unrelated_device_key", null))
        }
    }

    @Test
    fun `lastKnown reads back as null for absent or negative stored value (fail-closed)`() {
        assertNull(tokenManager.getLastKnownUserId()) // key 不存在

        // 脏/被篡改的负值必须判不可知，而不是当成合法 account id
        prefs.edit().putInt(keyLastKnownUserId, -5).commit()
        assertNull(tokenManager.getLastKnownUserId())

        // round-trip 自证：合法值（含边界 0）必须能读回，
        // 否则上面的 null 可能只是因为 key 名写错而非「负值被拦下」
        prefs.edit().putInt(keyLastKnownUserId, 0).commit()
        assertEquals(0, tokenManager.getLastKnownUserId())
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

    @Test
    fun `session snapshot is invalidated by relogin even with identical credentials`() {
        tokenManager.saveSession("same-jwt", 7, "alice", "Alice")
        val old = tokenManager.sessionSnapshot()
        assertEquals("same-jwt", old.token)
        assertEquals(tokenManager.sessionGeneration(), old.generation)
        tokenManager.saveSession("same-jwt", 7, "alice", "Alice")
        assertTrue(tokenManager.sessionGeneration() > old.generation)
        assertFalse(tokenManager.endSessionIfCurrent(old.generation, SessionEndReason.TOKEN_EXPIRED))
        assertFalse(tokenManager.updateToken("late-password-token", old.generation))
        assertEquals("same-jwt", tokenManager.getToken())
        assertNull(tokenManager.getSessionEndReason())
    }

    @Test
    fun `token update preserves every other stored field and advances generation`() {
        tokenManager.saveSession("old", 7, "alice", "Alice")
        prefs.edit().putString("device_setting", "keep").commit()
        val before = prefs.all.filterKeys { it != "jwt_token" }
        val generation = tokenManager.sessionGeneration()
        assertTrue(tokenManager.updateToken("new", generation))
        assertEquals("new", tokenManager.getToken())
        assertEquals(before, prefs.all.filterKeys { it != "jwt_token" })
        assertTrue(tokenManager.sessionGeneration() > generation)
        assertFalse(tokenManager.endSessionIfCurrent(generation, SessionEndReason.TOKEN_EXPIRED))
        assertFalse(tokenManager.updateToken("stale", generation))
    }

    @Test
    fun `ended or empty session cannot be resurrected by password response`() {
        tokenManager.saveSession("old", 7, "alice", null)
        val generation = tokenManager.sessionGeneration()
        assertFalse(tokenManager.updateToken(" ", generation))
        assertEquals("old", tokenManager.getToken())
        tokenManager.endSession(SessionEndReason.SIGNED_OUT)
        assertTrue(tokenManager.sessionGeneration() > generation)
        assertFalse(tokenManager.updateToken("late", generation))
        assertFalse(tokenManager.updateToken("late", tokenManager.sessionGeneration()))
        assertFalse(tokenManager.endSessionIfCurrent(tokenManager.sessionGeneration(), SessionEndReason.TOKEN_EXPIRED))
        assertNull(tokenManager.getToken())
        assertEquals(SessionEndReason.SIGNED_OUT, tokenManager.getSessionEndReason())
    }

    @Test
    fun `failed token persist keeps generation and current token`() {
        val failing = io.mockk.mockk<SharedPreferences>()
        val editor = io.mockk.mockk<SharedPreferences.Editor>(relaxed = true)
        io.mockk.every { failing.getString("jwt_token", null) } returns "old"
        io.mockk.every { failing.edit() } returns editor
        io.mockk.every { editor.putString(any(), any()) } returns editor
        io.mockk.every { editor.commit() } returns false
        val manager = TokenManager(failing)
        val generation = manager.sessionGeneration()

        assertFalse(manager.updateToken("new", generation))
        assertEquals(generation, manager.sessionGeneration())
        assertEquals("old", manager.getToken())
    }

    @Test
    fun `concurrent compare-and-end has exactly one winner`() {
        tokenManager.saveSession("old", 7, "alice", null)
        val generation = tokenManager.sessionGeneration()
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map {
                executor.submit<Boolean> {
                    start.await()
                    tokenManager.endSessionIfCurrent(generation, SessionEndReason.TOKEN_EXPIRED)
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(7, tokenManager.getLastKnownUserId())
            assertNull(tokenManager.getToken())
        } finally {
            executor.shutdownNow()
        }
    }
}
