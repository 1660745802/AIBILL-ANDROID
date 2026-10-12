package com.aibill.android.presentation.ui.settings

import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.domain.repository.AuthRepository
import com.aibill.android.service.NotificationRulesManager
import com.aibill.android.service.UpdateManager
import com.aibill.android.util.AppLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 通知 AI 解析总闸的接线测试。
 *
 * `UserPreferences.aiParseEnabled` 被 `NotificationInterpreter` 读取：false 时**整条通知入账被丢弃**。
 * 在这个开关接上设置页之前，它是一个用户永远改不了的闸门。本测试钉住：
 * 「设置页开关 → 写入偏好 → ViewModel 暴露的 StateFlow 读到新值」这条链路确实通了。
 *
 * 用 mock 的 [UserPreferences]（`aiParseEnabled` 背后挂 [MutableStateFlow]，setter 写回它），
 * 因为这里验证的是**转发**而不是 DataStore 持久化本身——后者由
 * `UserPreferencesClearUserInfoTest` 用真实文件型 DataStore 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val appLogger: AppLogger = mockk(relaxed = true)
    private val notificationRulesManager: NotificationRulesManager = mockk(relaxed = true)
    private val updateManager: UpdateManager = mockk(relaxed = true)

    private val aiParse = MutableStateFlow(true)
    private val userPreferences: UserPreferences = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // SettingsViewModel 的 init 会把所有偏好 stateIn 成 StateFlow，全部要能安全打桩。
        every { userPreferences.nickname } returns flowOf("alice")
        every { userPreferences.username } returns flowOf("alice")
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.serverUrl } returns flowOf("https://bill.example.com/api")
        every { userPreferences.hideFromRecents } returns flowOf(false)
        every { userPreferences.notificationPrivacy } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.quickEntryEnabled } returns flowOf(false)
        every { userPreferences.dynamicColorEnabled } returns flowOf(false)
        every { userPreferences.aiParseEnabled } returns aiParse
        // setter 写回同一个 MutableStateFlow，模拟真实持久化后的重新发射
        coEvery { userPreferences.setAiParseEnabled(any()) } answers { aiParse.value = firstArg() }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = SettingsViewModel(
        authRepository = authRepository,
        userPreferences = userPreferences,
        appLogger = appLogger,
        notificationRulesManager = notificationRulesManager,
        updateManager = updateManager,
    )

    @Test
    fun `defaults to enabled`() {
        assertEquals(true, buildViewModel().aiParseEnabled.value)
    }

    @Test
    fun `toggling the gate writes the preference and the exposed flow follows`() = runTest {
        val viewModel = buildViewModel()
        assertEquals(true, viewModel.aiParseEnabled.value)

        viewModel.onAiParseChanged(false)
        coVerify(exactly = 1) { userPreferences.setAiParseEnabled(false) }
        assertEquals(false, viewModel.aiParseEnabled.value)

        viewModel.onAiParseChanged(true)
        coVerify(exactly = 1) { userPreferences.setAiParseEnabled(true) }
        assertEquals(true, viewModel.aiParseEnabled.value)
    }

    @Test
    fun `display name falls back from blank nickname and username to 用户`() {
        every { userPreferences.nickname } returns flowOf("   ")
        every { userPreferences.username } returns flowOf("bob")
        assertEquals("bob", buildViewModel().displayName.value)

        every { userPreferences.username } returns flowOf("")
        assertEquals("用户", buildViewModel().displayName.value)
    }
}
