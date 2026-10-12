package com.aibill.android.data.local.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `UserPreferences.clearUserInfo()` 的**真实**测试（Robolectric + 真实文件型 DataStore）。
 *
 * ## 为什么不能用 mock
 *
 * 这里要证明的是「哪些 key 被删、哪些 key 被留」。用 mockk 只能证明
 * `clearUserInfo()` 被调用过，证不了它删了哪几个 key —— 而那正是全部风险所在。
 *
 * ## 真实事故（F5）
 *
 * 登出原本调用 `UserPreferences.clear()`，实现是 `dataStore.edit { it.clear() }`：
 * 抹掉**全部** key。而 `server_url` 也在同一个 DataStore 里（`Keys.SERVER_URL`），
 * 于是登出后 `ServerUrlInterceptor` 读不到 serverUrl → 请求回落到占位地址
 * `http://localhost:3000/api/` → **用户登出后再也无法登录**，必须重新配置服务器。
 * 设备设置（主题 / 通知隐私 / 应用锁 / AI 开关）也被一并清掉。
 *
 * 与此同时，`ProfileScreen` 的登出确认弹窗承诺的是「本地未同步的数据不会丢失」——
 * 实现却在顺手 nuked 用户的服务器地址和全部设置。
 *
 * 本测试钉住：**登出只清身份三项，其余全部保留。**
 *
 * ## 为什么用 runBlocking 而不是 runTest
 *
 * 这里操作的是真实文件型 DataStore，其内部跑在 `Dispatchers.IO` 上，
 * 与 `runTest` 的虚拟时间不兼容；必须用真实挂起等待。
 *
 * `application = Application::class` 必需：默认会按 manifest 启动真实 `AiBillApp`，
 * 其 Hilt 图会构造 `TokenManager` → `EncryptedSharedPreferences` → `AndroidKeyStore`，
 * 在 Robolectric 沙箱里抛 `KeyStoreException: AndroidKeyStore not found`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UserPreferencesClearUserInfoTest {

    private lateinit var prefs: UserPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric 每个测试给新的 Application → filesDir 独立；
        // 这里再显式删一次文件，确保不残留上一轮数据。
        File(context.filesDir, "datastore/user_preferences.preferences_pb").delete()
        // 直接构造真实类（其 dataStore 是 Context 扩展委托，与生产同一个单例文件）
        prefs = UserPreferences(context)
    }

    @Test
    fun `clearUserInfo removes identity but keeps serverUrl and every device setting`() = runBlocking {
        prefs.setServerUrl("https://bill.example.com/api")
        prefs.setUserInfo(42, "alice", "Alice")
        prefs.setThemeMode("dark")
        prefs.setNotificationPrivacy(false)
        prefs.setAppLockEnabled(true)
        prefs.setAiParseEnabled(false)
        prefs.setHideFromRecents(true)
        prefs.setQuickEntryEnabled(true)
        prefs.setDynamicColorEnabled(true)

        prefs.clearUserInfo()

        // 身份三项被清
        assertNull("user_id 应被清", prefs.userId.first())
        assertNull("username 应被清", prefs.username.first())
        assertNull("nickname 应被清", prefs.nickname.first())

        // 服务器地址必须保留 —— 否则登出后无法再登录（F5）
        assertEquals("https://bill.example.com/api", prefs.serverUrl.first())
        // 设备设置与偏好全部保留
        assertEquals("dark", prefs.themeMode.first())
        assertEquals(false, prefs.notificationPrivacy.first())
        assertEquals(true, prefs.appLockEnabled.first())
        assertEquals(false, prefs.aiParseEnabled.first())
        assertEquals(true, prefs.hideFromRecents.first())
        assertEquals(true, prefs.quickEntryEnabled.first())
        assertEquals(true, prefs.dynamicColorEnabled.first())
    }

    @Test
    fun `clearUserInfo is idempotent`() = runBlocking {
        prefs.setServerUrl("https://bill.example.com/api")
        prefs.setUserInfo(42, "alice", "Alice")

        prefs.clearUserInfo()
        prefs.clearUserInfo() // 再次调用不得抛异常，也不得误删 serverUrl

        assertNull(prefs.userId.first())
        assertEquals("https://bill.example.com/api", prefs.serverUrl.first())
    }
}
